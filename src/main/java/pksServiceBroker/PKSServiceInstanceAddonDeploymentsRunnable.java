package pksServiceBroker;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import pksServiceBroker.Config.BrokerAction;
import pksServiceBroker.Config.RoutingLayer;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.KubernetesClusterRoleBinding;

import org.apache.commons.validator.routines.InetAddressValidator;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.servicebroker.model.instance.OperationState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.io.FileNotFoundException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "routereg")
public class PKSServiceInstanceAddonDeploymentsRunnable implements Runnable {

	@Value("${routereg.route_api_client.client_id}")
	private String routeClient;

	@Value("${routereg.route_api_client.client_secret}")
	private String routeClientSecret;

	@Value("${routereg.externalPortRange}")
	private String portRange;

	@Autowired
	pksServiceBroker.Config sbConfig;
	private static String clusterConfigMapFilename = "config/kibosh-external-hostnames-config-map.yaml";
	private static String routeEmitDeploymentFilename = "config/route-reg-deployment.yaml";
	private static String kiboshDeploymentFilename = "config/kibosh-deployment.yaml";
	private static String kiboshRBACAccountFilename = "config/kibosh-rbac-service-account.yaml";
	private static String kiboshRBACBindingFilename = "config/kibosh-rbac-cr-binding.yaml";
	private static String kiboshServiceFilename = "config/kibosh-service.yaml";
	private static String bazaarServiceFilename = "config/kibosh-bazaar-service.yaml";

	
	private static Logger LOG = LogManager.getLogger(PKSServiceInstanceAddonDeploymentsRunnable.class);

	private String operationStateMessage;
	private OperationState state;
	private int kubeExternalPort = 0;
	private int kiboshExternalPort = 0;
	private int bazaarExternalPort = 0;
	private KubernetesClient client = new DefaultKubernetesClient();;
	private BrokerAction action;
	private RoutingLayer kiboshRoutingLayer = RoutingLayer.HTTP;
	private JSONObject jsonClusterInfo;
	private HttpHeaders pksHeaders;
	private HttpHeaders routeHeaders;
	private HttpEntity<String> routeRequestObject;
	private HttpEntity<String> pksRequestObject;
	private String serviceInstanceId;
	private String pksPlanName;
	@Autowired
	@Qualifier("pks")
	OAuth2RestTemplate pksRestTemplate;

	@Autowired
	@Qualifier("route")
	OAuth2RestTemplate routeRestTemplate;

	private ConfigMap clusterConfigMap;

	@Bean(name = "addonDeploymentRunnable")
	@Scope(value = "prototype")
	public PKSServiceInstanceAddonDeploymentsRunnable getPKSServiceInstanceAddonDeploymentsRunnable(BrokerAction action,
			String serviceInstanceId, String planName, RoutingLayer kiboshRoutingLayer) {
		PKSServiceInstanceAddonDeploymentsRunnable runner = new PKSServiceInstanceAddonDeploymentsRunnable();
		// INITIALIZATION
		runner.state = OperationState.IN_PROGRESS;
		runner.operationStateMessage = "Preparing deployment";
		runner.action = action;
		runner.serviceInstanceId = serviceInstanceId;
		runner.kiboshRoutingLayer = kiboshRoutingLayer;
		runner.pksPlanName = planName;

		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", sbConfig.PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + pksRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		runner.pksHeaders = headers;
		headers = new HttpHeaders();
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + routeRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		runner.routeHeaders = headers;

		runner.pksRequestObject = new HttpEntity<String>("", runner.pksHeaders);
		runner.routeRequestObject = new HttpEntity<String>("", runner.routeHeaders);
		JSONArray master_ips;
		switch (runner.action) {
		case UPDATE:
			runner.jsonClusterInfo = new JSONObject(pksRestTemplate.getForObject(
					"https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + runner.serviceInstanceId, String.class));
			master_ips = (JSONArray) runner.jsonClusterInfo.get("kubernetes_master_ips");
			runner.kubeExternalPort = runner.jsonClusterInfo.getJSONObject("parameters")
					.getInt("kubernetes_master_port");

			// CHECK CLUSTER FOR CONFIGMAP DATA, IF NONE CREATE CONFIGMAP WITH CLUSTER DATA
			// WRITE TO CLUSTER
			try {
				JSONObject jsonClusterContext = new JSONObject(pksRestTemplate.postForObject(
						"https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + runner.serviceInstanceId + "/binds",
						runner.pksRequestObject, String.class));
				jsonClusterContext.put("master_ip", master_ips.getString(0));
				refreshClient(jsonClusterContext, true, true);
				LOG.info("ConfigMap with ClusterData found on PKS Cluster " + runner.serviceInstanceId);
				runner.setClusterConfigMap(
						client.configMaps().withName("cluster-addon-deployment-data").fromServer().get());
				switch (runner.kiboshRoutingLayer) {
				case HTTP:
					runner.kiboshExternalPort = 0;
					runner.bazaarExternalPort = 0;
					runner.clusterConfigMap.getData().put("bazaar.port",
							runner.bazaarExternalPort == 0 ? "443" : String.valueOf(runner.bazaarExternalPort));
					runner.clusterConfigMap.getData().put("kibosh.port",
							runner.kiboshExternalPort == 0 ? "443" : String.valueOf(runner.kiboshExternalPort));
					break;
				case TCP:
					runner.kiboshExternalPort = Integer
							.valueOf(runner.clusterConfigMap.getData().get("kibosh.port")) == 443
									? getFreePortForCFTcpRouter()
									: Integer.valueOf(runner.clusterConfigMap.getData().get("kibosh.port"));
					runner.bazaarExternalPort = Integer
							.valueOf(runner.clusterConfigMap.getData().get("bazaar.port")) == 443
									? getFreePortForCFTcpRouter()
									: Integer.valueOf(runner.clusterConfigMap.getData().get("bazaar.port"));
					runner.clusterConfigMap.getData().put("bazaar.port", String.valueOf(runner.bazaarExternalPort));
					runner.clusterConfigMap.getData().put("kibosh.port", String.valueOf(runner.kiboshExternalPort));
					break;
				default:
					break;
				}
				String prefix = runner.kiboshRoutingLayer.equals(RoutingLayer.HTTP) ? "https://" : "http://";
				runner.clusterConfigMap.getData().put("kibosh.fqdn",
						getComponentHostname(pksServiceBroker.Config.KIBOSH_NAME, runner));
				runner.clusterConfigMap.getData().put("bazaar.fqdn",
						getComponentHostname(pksServiceBroker.Config.BAZAAR_NAME, runner));
				runner.clusterConfigMap.getData().put("kibosh.protocoll", prefix);
				runner.clusterConfigMap.getData().put("bazaar.protocoll", prefix);
			} catch (Exception e) {
				if (runner.kiboshRoutingLayer.equals(RoutingLayer.TCP)) {
					runner.kiboshExternalPort = getFreePortForCFTcpRouter();
					runner.bazaarExternalPort = getFreePortForCFTcpRouter();
				}
				runner.clusterConfigMap = createConfigMap(runner);
			}

			break;
		case CREATE:
			runner.kubeExternalPort = getFreePortForCFTcpRouter();
			JSONObject payload = new JSONObject().put("name", runner.serviceInstanceId)
					.put("plan_name", runner.pksPlanName)
					.put("parameters", new JSONObject().put("kubernetes_master_host", sbConfig.TCP_FQDN)
							.put("kubernetes_master_port", runner.kubeExternalPort));
			runner.pksRequestObject = new HttpEntity<String>(payload.toString(), runner.pksHeaders);
			runner.clusterConfigMap = createConfigMap(runner);
			break;
		case GET:
			LOG.info("Retrieving Cluster Info for PKS Cluster: " + runner.serviceInstanceId + " from "
					+ sbConfig.PKS_FQDN);
			runner.jsonClusterInfo = new JSONObject(pksRestTemplate.getForObject(
					"https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + runner.serviceInstanceId, String.class));
			master_ips = (JSONArray) runner.jsonClusterInfo.get("kubernetes_master_ips");
			runner.kubeExternalPort = runner.jsonClusterInfo.getJSONObject("parameters")
					.getInt("kubernetes_master_port");

			// CHECK CLUSTER FOR CONFIGMAP DATA, IF NONE CREATE CONFIGMAP WITH CLUSTER DATA
			// WRITE TO CLUSTER
			try {
				JSONObject jsonClusterContext = new JSONObject(pksRestTemplate.postForObject(
						"https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + runner.serviceInstanceId + "/binds",
						runner.pksRequestObject, String.class));
				jsonClusterContext.put("master_ip", master_ips.get(0));
				refreshClient(jsonClusterContext, true, true);
				LOG.info("ConfigMap with ClusterData found on PKS Cluster " + runner.serviceInstanceId);
				runner.setClusterConfigMap(
						client.configMaps().withName("cluster-addon-deployment-data").fromServer().get());
			} catch (Exception e) {
				LOG.error("PKS Cluster " + runner.serviceInstanceId + " has no ConfigMap yet. Run UPDATE");
			}
		default:
			break;
		}
		client.close();
		return runner;
	}

	public PKSServiceInstanceAddonDeploymentsRunnable() {
		this.state = OperationState.IN_PROGRESS;
		this.operationStateMessage = "Started Deployment";
	}

	private String getComponentHostname(String componentName, PKSServiceInstanceAddonDeploymentsRunnable runner) {
		switch (runner.kiboshRoutingLayer) {
		case TCP:
			return sbConfig.TCP_FQDN;
		case HTTP:
			return componentName + "-" + runner.serviceInstanceId + "." + sbConfig.APPS_FQDN;
		default:
			break;
		}

		return "";
	}

	private ConfigMap createConfigMap(PKSServiceInstanceAddonDeploymentsRunnable runner) {
		LOG.info("Config Map not found on PKS Cluster " + runner.serviceInstanceId + ". Creating...");
		ConfigMap clusterConfigMap = runner.client.configMaps().load(PKSServiceInstanceAddonDeploymentsRunnable.class
				.getClassLoader().getResourceAsStream(clusterConfigMapFilename)).get();

		String prefix = runner.kiboshRoutingLayer.equals(RoutingLayer.HTTP) ? "https://" : "http://";
		if (runner.kiboshRoutingLayer.equals(RoutingLayer.TCP)) {
			runner.kiboshExternalPort = getFreePortForCFTcpRouter();
			runner.bazaarExternalPort = getFreePortForCFTcpRouter();
			LOG.debug("Reserving Kibosh Port " + runner.kiboshExternalPort + "; Bazaar Port: "
					+ runner.bazaarExternalPort);
		}
		clusterConfigMap.getData().put("kibosh.fqdn",
				getComponentHostname(pksServiceBroker.Config.KIBOSH_NAME, runner));
		clusterConfigMap.getData().put("kibosh.port",
				runner.kiboshExternalPort == 0 ? "443" : Integer.toString(runner.kiboshExternalPort));
		clusterConfigMap.getData().put("kibosh.user", java.util.UUID.randomUUID().toString());
		clusterConfigMap.getData().put("kibosh.password", java.util.UUID.randomUUID().toString());
		clusterConfigMap.getData().put("kibosh.protocoll", prefix);
		clusterConfigMap.getData().put("bazaar.fqdn",
				getComponentHostname(pksServiceBroker.Config.KIBOSH_NAME, runner));
		clusterConfigMap.getData().put("bazaar.user", java.util.UUID.randomUUID().toString());
		clusterConfigMap.getData().put("bazaar.password", java.util.UUID.randomUUID().toString());
		clusterConfigMap.getData().put("bazaar.port",
				runner.bazaarExternalPort == 0 ? "443" : Integer.toString(runner.bazaarExternalPort));
		clusterConfigMap.getData().put("bazaar.protocoll", prefix);

		clusterConfigMap.getData().put("kubernetes.fqdn", sbConfig.TCP_FQDN);
		clusterConfigMap.getData().put("kubernetes.port", Integer.toString(runner.kubeExternalPort));
		clusterConfigMap.getData().put("kubernetes.protocoll", "https://");

		return clusterConfigMap;
	}

	private void refreshClient(JSONObject kubeConfig, boolean internalRoute, Boolean namespaced) {
		client.close();
		String masterURL = "";
		if (internalRoute) {
			masterURL = "https://" + kubeConfig.getString("master_ip") + ":"
					+ pksServiceBroker.Config.KUBERNETES_MASTER_PORT + "/";
		} else {
			masterURL = kubeConfig.getJSONArray("clusters").getJSONObject(0).getJSONObject("cluster")
					.getString("server");
		}
		Config config;
		if (namespaced)
			config = new ConfigBuilder().withMasterUrl(masterURL)
					.withOauthToken(
							kubeConfig.getJSONArray("users").getJSONObject(0).getJSONObject("user").getString("token"))
					.withCaCertData(kubeConfig.getJSONArray("clusters").getJSONObject(0).getJSONObject("cluster")
							.getString("certificate-authority-data"))
					.withNamespace(pksServiceBroker.Config.ADDON_NAMESPACE).withTrustCerts(true).build();
		else
			config = new ConfigBuilder().withMasterUrl(masterURL)
					.withOauthToken(
							kubeConfig.getJSONArray("users").getJSONObject(0).getJSONObject("user").getString("token"))
					.withCaCertData(kubeConfig.getJSONArray("clusters").getJSONObject(0).getJSONObject("cluster")
							.getString("certificate-authority-data"))
					.withTrustCerts(true).build();

		client = new DefaultKubernetesClient(config);
	}

	protected int getFreePortForCFTcpRouter() {
		int portMin = Integer.parseInt(portRange.split("-")[0]);
		int portMax = Integer.parseInt(portRange.split("-")[1]);
		int portMaster = new Random().nextInt(portMax - portMin + 1) + portMin;
		Set<Integer> ports = new LinkedHashSet<>();
		JSONArray routes = new JSONArray(
				routeRestTemplate.exchange("https://" + sbConfig.PCF_API + "/routing/v1/tcp_routes", HttpMethod.GET,
						routeRequestObject, String.class).getBody().toString());
		routes.forEach((route) -> {
			ports.add(new JSONObject(route.toString()).getInt("port"));
		});
		while (ports.contains(portMaster))
			portMaster = new Random().nextInt(portMax - portMin + 1) + portMin;
		return portMaster;
	}

	protected Boolean checkRouteRegDeployment(JSONObject kubeConfig, String componentName) {
		Boolean cont = true;
		refreshClient(kubeConfig, true, true);
		String deploymentName = pksServiceBroker.Config.ROUTE_DEPLOYMENT_PREFIX + componentName;
		Deployment deployment = client.apps().deployments().withName(deploymentName).fromServer().get();
		if (deployment == null) {
			state = OperationState.FAILED;
			cont = false;
		} else {
			refreshClient(kubeConfig, false, false);
			if (client != null) {
				try {
					deployment = client.apps().deployments().withName(deploymentName).fromServer().get();
					cont = false;
					LOG.info("Master Routes active on PKS Cluster " + serviceInstanceId);
					operationStateMessage = "Master Routes active";
				} catch (KubernetesClientException e) {
					// our try used external url, so we use Exception to figure out whether route is
					// ready
					LOG.info("Waiting for Master Routes to become active on PKS Cluster " + serviceInstanceId);
					operationStateMessage = "Waiting for Master Routes to become active";
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		client.close();
		return cont;
	}

	public void createRouteRegDeployment(JSONObject kubeConfig, int internalPort, List<Object> internalIPs,
			int externalPort, String componentName, RoutingLayer routingLayer) throws FileNotFoundException {
		String deploymentName = pksServiceBroker.Config.ROUTE_DEPLOYMENT_PREFIX + componentName;
		refreshClient(kubeConfig, true, true);
		Deployment routeEmitDeployment = client.apps().deployments()
				.load(PKSServiceInstanceAddonDeploymentsRunnable.class.getClassLoader()
						.getResourceAsStream(routeEmitDeploymentFilename))
				.get();

		if (routeEmitDeployment instanceof Deployment) {
			routeEmitDeployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().iterator()
					.forEachRemaining((EnvVar envVar) -> {
						switch (envVar.getName()) {
						case "CF_API_FQDN": {
							envVar.setValue(sbConfig.PCF_API);
							break;
						}
						case "TCP_ROUTER_PORT": {
							if (routingLayer.equals(RoutingLayer.HTTP)) {
								envVar.setValue(componentName + "-" + serviceInstanceId + "." + sbConfig.APPS_FQDN);
								envVar.setName("CF_APPS_FQDN");
							} else
								envVar.setValue(Integer.toString(externalPort));
							break;
						}
						case "SERVICE_ID": {
							envVar.setValue(serviceInstanceId);
							break;
						}
						case "SERVICE_PORT": {
							envVar.setValue(String.valueOf(internalPort));
							break;
						}
						case "SERVICE_IP": {
							StringJoiner ipCSV = new StringJoiner(",");
							internalIPs.forEach(ip -> {
								ipCSV.add((CharSequence) ip);
							});
							envVar.setValue(ipCSV.toString());
							break;
						}
						case "ROUTE_CLIENT": {
							envVar.setValue(routeClient);
							break;
						}
						case "ROUTE_CLIENT_SECRET": {
							envVar.setValue(routeClientSecret);
							break;
						}
						case "ROUTE_TTL": {
							envVar.setValue(Integer.toString(pksServiceBroker.Config.ROUTE_TTL));
							break;
						}
						}
					});
			routeEmitDeployment.getMetadata().setName(deploymentName);
			routeEmitDeployment.getSpec().getTemplate().getMetadata().getLabels().put("name", deploymentName);
			routeEmitDeployment.getSpec().getSelector().getMatchLabels().put("name", deploymentName);
			routeEmitDeployment.getSpec().getTemplate().getSpec().getContainers().get(0).setName(deploymentName);
			routeEmitDeployment = client.apps().deployments().createOrReplace(routeEmitDeployment);
			LOG.info("Created Deployment of RouteRegistration for " + componentName + " on PKS Cluster "
					+ serviceInstanceId);

		} else {
			LOG.error("Loaded resource is not a Deployment! Could not create RouteReg Deployment for " + componentName
					+ " on PKS Cluster " + serviceInstanceId);
		}
		client.close();
	}

	protected void createKiboshDeployment(JSONObject jsonClusterContext) throws FileNotFoundException {
		refreshClient(jsonClusterContext, false, true);

		Deployment kiboshDeployment = client.apps().deployments().load(PKSServiceInstanceAddonDeploymentsRunnable.class
				.getClassLoader().getResourceAsStream(kiboshDeploymentFilename)).get();
		Service kiboshBazaarService = client.services().load(PKSServiceInstanceAddonDeploymentsRunnable.class
				.getClassLoader().getResourceAsStream(bazaarServiceFilename)).get();
		Service kiboshService = client.services().load(PKSServiceInstanceAddonDeploymentsRunnable.class.getClassLoader()
				.getResourceAsStream(kiboshServiceFilename)).get();
		ServiceAccount kiboshRBACAccount = client.serviceAccounts()
				.load(PKSServiceInstanceAddonDeploymentsRunnable.class.getClassLoader()
						.getResourceAsStream(kiboshRBACAccountFilename))
				.get();
		KubernetesClusterRoleBinding kiboshRBACBinding = client.rbac().kubernetesClusterRoleBindings()
				.load(PKSServiceInstanceAddonDeploymentsRunnable.class.getClassLoader()
						.getResourceAsStream(kiboshRBACBindingFilename))
				.get();
		if (kiboshRBACAccount instanceof ServiceAccount) {
			try {
				kiboshRBACAccount = client.serviceAccounts().createOrReplace(kiboshRBACAccount);
				LOG.info("Created Kibosh Helm Service Account for PKS Cluster " + serviceInstanceId);
			} catch (KubernetesClientException e) {
				state = OperationState.FAILED;
				LOG.error("Error Creating Service Account " + kiboshRBACAccount.toString() + " on PKS Cluster "
						+ serviceInstanceId);
				e.printStackTrace();
			}
		} else {
			state = OperationState.FAILED;
			LOG.error("Loaded resource is not an Account! " + serviceInstanceId);
		}
		if (kiboshDeployment instanceof Deployment) {
			try {
				kiboshDeployment = client.apps().deployments().createOrReplace(kiboshDeployment);
				LOG.info("Created Kibosh Deployment on PKS Cluster " + serviceInstanceId);
			} catch (KubernetesClientException e) {
				state = OperationState.FAILED;
				LOG.error("Error Creating Kibosh Deployment " + kiboshDeployment.toString() + " on PKS Cluster "
						+ serviceInstanceId);
				e.printStackTrace();
			}
		} else {
			state = OperationState.FAILED;
			LOG.error("Loaded resource is not a Deployment! " + serviceInstanceId);
		}

		if (kiboshBazaarService instanceof Service) {
			try {
				kiboshBazaarService = client.services().createOrReplace(kiboshBazaarService);
				LOG.info("Created Bazaar Service on PKS Custer " + serviceInstanceId);
			} catch (KubernetesClientException e) {
				state = OperationState.FAILED;
				LOG.error("Failed creating Bazaar Service on PKS Custer " + serviceInstanceId);
				e.printStackTrace();
			}
		} else {
			state = OperationState.FAILED;
			LOG.error("Loaded resource is not a Service!" + serviceInstanceId);
		}
		if (kiboshService instanceof Service) {
			try {
				kiboshService = client.services().createOrReplace(kiboshService);
				LOG.info("Created KIBOSH Service on PKS Custer " + serviceInstanceId);
			} catch (KubernetesClientException e) {
				state = OperationState.FAILED;
				LOG.error("Failed creating KIBOSH Service on PKS Custer " + serviceInstanceId);
				e.printStackTrace();
			}

		} else {
			state = OperationState.FAILED;
			LOG.error("Loaded resource is not a Service! " + serviceInstanceId);
		}
		if (kiboshRBACBinding instanceof KubernetesClusterRoleBinding) {
			try {
				client.rbac().kubernetesClusterRoleBindings().createOrReplace(kiboshRBACBinding);
				LOG.info("Created Kibosh Helm Tiller ClusterRoleBinding on PKS Custer " + serviceInstanceId);
			} catch (KubernetesClientException e) {
				LOG.error("Failed Creating Kibosh Helm Tiller ClusterRoleBinding on PKS Custer " + serviceInstanceId);
				state = OperationState.FAILED;
				e.printStackTrace();
			}

		} else {
			state = OperationState.FAILED;
			LOG.error("Loaded resource is not a Role Binding! " + serviceInstanceId);
		}

		JSONArray nodeIPs = new JSONArray();
		client.nodes().list().getItems().forEach((Node node) -> {
			nodeIPs.put(node.getStatus().getAddresses().get(0).getAddress());
		});

		LOG.debug("Will use NodeIPs: " + nodeIPs.toString() + " for route Registration on PKS Cluster: "
				+ serviceInstanceId);
		createRouteRegDeployment(jsonClusterContext, kiboshService.getSpec().getPorts().get(0).getNodePort(),
				nodeIPs.toList(), kiboshExternalPort, pksServiceBroker.Config.KIBOSH_NAME, kiboshRoutingLayer);
		createRouteRegDeployment(jsonClusterContext, kiboshBazaarService.getSpec().getPorts().get(0).getNodePort(),
				nodeIPs.toList(), bazaarExternalPort, pksServiceBroker.Config.BAZAAR_NAME, kiboshRoutingLayer);
		client.close();
	}

	@Override
	public void run() {

		JSONArray master_ips = new JSONArray();
		master_ips.put("empty");

		// SWITCH TO HANDLE CHANGES IN WORKFLOWS
		switch (action) {
		case CREATE:
			LOG.debug("Create PKS Cluster for ServiceID " + serviceInstanceId + " Requestobject: "
					+ pksRequestObject.toString());
			pksRestTemplate.postForObject("https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters", pksRequestObject,
					String.class);
			break;
		case UPDATE:
			break;
		default:
			break;
		}
		// CHECK CLUSTER CREATION PROCESS UNTIL ITS DONE
		while (!InetAddressValidator.getInstance().isValid(master_ips.get(0).toString())
				&& (state != OperationState.FAILED || state != OperationState.SUCCEEDED)) {
			jsonClusterInfo = new JSONObject(pksRestTemplate.getForObject(
					"https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId, String.class));
			try {
				master_ips = (JSONArray) jsonClusterInfo.get("kubernetes_master_ips");
				LOG.debug("PKS Cluster " + serviceInstanceId + " deployment in progress."
						+ jsonClusterInfo.getString("last_action_description"));
				this.state = jsonClusterInfo.get("last_action_state").equals("failed") ? OperationState.FAILED
						: OperationState.IN_PROGRESS;
				this.operationStateMessage = jsonClusterInfo.getString("last_action_description");

			} catch (HttpClientErrorException e) {
				switch (e.getStatusCode().toString()) {
				case "404":
					LOG.info("PKS Cluster: " + serviceInstanceId + " could not be found");
				case "401":
					LOG.error("Broker could not authenticate with PKS API");
					break;
				default:
					break;
				}
			}
			try {
				TimeUnit.SECONDS.sleep(10);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		if (this.state.equals(OperationState.FAILED))
			return;

		LOG.info("Succesfully deployed PKS Cluster " + serviceInstanceId);
		LOG.info("Applying Addons to : " + serviceInstanceId);

		// GET CLUSTER INFO
		jsonClusterInfo = new JSONObject(pksRestTemplate
				.getForObject("https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId, String.class));

		// GET CLUSTER CONTEXT
		LOG.debug("Getting credentials for " + serviceInstanceId);
		JSONObject jsonClusterContext = new JSONObject(pksRestTemplate.postForObject(
				"https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId + "/binds", pksRequestObject,
				String.class));

		jsonClusterContext.put("master_ip", master_ips.getString(0));
		refreshClient(jsonClusterContext, true, true);
		// CREATE CONFIGMAP
		if (clusterConfigMap instanceof ConfigMap) {
			try {
				clusterConfigMap = client.configMaps().createOrReplace(clusterConfigMap);
				LOG.info("Created Cluster ConfigMap for PKS Cluster " + serviceInstanceId);
			} catch (KubernetesClientException e) {
				state = OperationState.FAILED;
				LOG.error("Error Creating Config Map" + clusterConfigMap.toString() + " on PKS Cluster "
						+ serviceInstanceId);
				LOG.error(e);
			}
		} else {
			LOG.error("ConfigMap not found, something went wrong in initialization");
		}

		// CREATE ROUTE REG DEPLOYMENT FOR MASTER
		try {
			operationStateMessage = "Cluster created, creating route emitter pod";
			LOG.info("Deploying Addons on PKS Cluster " + serviceInstanceId);
			createRouteRegDeployment(jsonClusterContext, pksServiceBroker.Config.KUBERNETES_MASTER_PORT,
					jsonClusterInfo.getJSONArray("kubernetes_master_ips").toList(),
					jsonClusterInfo.getJSONObject("parameters").getInt("kubernetes_master_port"), "master",
					RoutingLayer.TCP);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		// CHECK IF PKS CLUSTER ALREADY HAS A ROUTE EMITTER POD RUNNING
		while (checkRouteRegDeployment(jsonClusterContext, "master") && !state.equals(OperationState.FAILED)) {
			try {
				TimeUnit.SECONDS.sleep(5);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		if (state.equals(OperationState.FAILED))
			return;
		operationStateMessage = "RouteRegistration for Master Complete";

		// DEPLOY KIBOSH POD
		try {
			createKiboshDeployment(jsonClusterContext);

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

		operationStateMessage = "finished deployment";
		state = OperationState.SUCCEEDED;
		client.close();
	}

	// GETTER & SETTER Block
	public BrokerAction getAction() {
		return action;
	}

	public OperationState getState() {
		return state;
	}

	public String getOperationStateMessage() {
		return operationStateMessage;
	}

	public ConfigMap getClusterConfigMap() {
		return clusterConfigMap;
	}

	private void setClusterConfigMap(ConfigMap clusterConfigMap) {
		this.clusterConfigMap = clusterConfigMap;
	}
}