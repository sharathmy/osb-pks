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
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.KubernetesClusterRole;
import io.fabric8.kubernetes.api.model.rbac.KubernetesClusterRoleBinding;
import io.fabric8.kubernetes.api.model.storage.StorageClass;

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
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

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
	private static String routeRegSecretFilename = "config/route-registrar-credentials.yaml";

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

	private Boolean useExternalRoute = false;

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
				changeClient(jsonClusterContext, "kube-system");
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
				client.close();
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
				changeClient(jsonClusterContext, "kube-system");
				LOG.info("ConfigMap with ClusterData found on PKS Cluster " + runner.serviceInstanceId);
				runner.setClusterConfigMap(
						client.configMaps().withName("cluster-addon-deployment-data").fromServer().get());
			} catch (Exception e) {
				client.close();
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
				getComponentHostname(pksServiceBroker.Config.BAZAAR_NAME, runner));
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

	private void changeClient(JSONObject jsonClusterContext, String namespace) {
		client.close();
		String masterURL = "";
		if (useExternalRoute) {
			masterURL = "https://" + jsonClusterContext.getString("master_ip") + ":"
					+ pksServiceBroker.Config.KUBERNETES_MASTER_PORT + "/";
		} else {
			masterURL = jsonClusterContext.getJSONArray("clusters").getJSONObject(0).getJSONObject("cluster")
					.getString("server");
		}
		Config config;
		if (namespace != null && namespace != "")
			config = new ConfigBuilder().withMasterUrl(masterURL)
					.withOauthToken(jsonClusterContext.getJSONArray("users").getJSONObject(0).getJSONObject("user")
							.getString("token"))
					.withCaCertData(jsonClusterContext.getJSONArray("clusters").getJSONObject(0)
							.getJSONObject("cluster").getString("certificate-authority-data"))
					.withNamespace(namespace).withTrustCerts(true).build();
		else
			config = new ConfigBuilder().withMasterUrl(masterURL)
					.withOauthToken(jsonClusterContext.getJSONArray("users").getJSONObject(0).getJSONObject("user")
							.getString("token"))
					.withCaCertData(jsonClusterContext.getJSONArray("clusters").getJSONObject(0)
							.getJSONObject("cluster").getString("certificate-authority-data"))
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

	protected Boolean checkRouteRegDeploymentMaster(JSONObject kubeConfig, String componentName) {
		Boolean cont = true;
		changeClient(kubeConfig, "kube-system");
		String deploymentName = pksServiceBroker.Config.ROUTE_DEPLOYMENT_PREFIX + componentName;
		Deployment deployment = client.apps().deployments().withName(deploymentName).fromServer().get();
		if (deployment == null) {
			state = OperationState.FAILED;
			client.close();
			cont = false;
		} else {
			changeClient(kubeConfig, null);
			if (client != null) {
				try {
					deployment = client.apps().deployments().withName(deploymentName).fromServer().get();
					cont = false;
					LOG.info("Master Routes active on PKS Cluster " + serviceInstanceId);
					setUseExternalRoute(true);
					operationStateMessage = "Master Routes active";
				} catch (KubernetesClientException e) {
					// our try used external url, so we use Exception to figure out whether route is
					// ready
					client.close();
					LOG.info("Waiting for Master Routes to become active on PKS Cluster " + serviceInstanceId);
					operationStateMessage = "Waiting for Master Routes to become active";
				} catch (Exception e) {
					client.close();
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
		changeClient(kubeConfig, "kube-system");
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
							break;
						}
						case "ROUTE_CLIENT_SECRET": {
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
			LOG.info(action.toString() + " Deployment of RouteRegistration for " + componentName + " on PKS Cluster "
					+ serviceInstanceId);

		} else {
			LOG.error("Loaded resource is not a Deployment! Could not create RouteReg Deployment for " + componentName
					+ " on PKS Cluster " + serviceInstanceId);
		}
		client.close();
	}

	protected void createKiboshDeployment(JSONObject jsonClusterContext) throws FileNotFoundException {
		applyResources(sbConfig.getDefaultAddons(), jsonClusterContext);
		JSONArray nodeIPs = new JSONArray();
		client.nodes().list().getItems().forEach((Node node) -> {
			nodeIPs.put(node.getStatus().getAddresses().get(0).getAddress());
		});

		LOG.debug("Will use NodeIPs: " + nodeIPs.toString() + " for route Registration on PKS Cluster: "
				+ serviceInstanceId);
		changeClient(jsonClusterContext, "kube-system");
		createRouteRegDeployment(jsonClusterContext, client.services().withName("kibosh-np").fromServer().get().getSpec().getPorts().get(0).getNodePort(),
				nodeIPs.toList(), kiboshExternalPort, pksServiceBroker.Config.KIBOSH_NAME, kiboshRoutingLayer);
		createRouteRegDeployment(jsonClusterContext, client.services().withName("kibosh-bazaar-np").fromServer().get().getSpec().getPorts().get(0).getNodePort(),
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
					+ pksRequestObject.toString().replaceAll("Bearer [a-zA-Z0-9-_.]*", "<redacted>"));
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
				client.close();
			}
			try {
				TimeUnit.SECONDS.sleep(10);
			} catch (InterruptedException e) {
				client.close();
				e.printStackTrace();
			}
		}

		if (this.state.equals(OperationState.FAILED)) {
			client.close();
			return;
		}

		LOG.info("Succesfully deployed PKS Cluster " + serviceInstanceId);
		LOG.info("Applying Default Operators to : " + serviceInstanceId);

		// GET CLUSTER CONTEXT
		LOG.debug("Getting credentials for " + serviceInstanceId);
		JSONObject jsonClusterContext = new JSONObject(pksRestTemplate.postForObject(
				"https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId + "/binds", pksRequestObject,
				String.class));

		jsonClusterContext.put("master_ip", master_ips.getString(0));

		applyResources(sbConfig.getDefaultOperators(), jsonClusterContext);

		LOG.info("Applying Default Addons to : " + serviceInstanceId);

		// GET CLUSTER INFO
		jsonClusterInfo = new JSONObject(pksRestTemplate
				.getForObject("https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId, String.class));

		// CREATE CONFIGMAP
		if (clusterConfigMap instanceof ConfigMap) {
			changeClient(jsonClusterContext, clusterConfigMap.getMetadata().getNamespace());
			try {
				clusterConfigMap = client.configMaps().createOrReplace(clusterConfigMap);
				LOG.info(action.toString() + " Cluster ConfigMap for PKS Cluster " + serviceInstanceId);
			} catch (KubernetesClientException e) {
				state = OperationState.FAILED;
				LOG.error("Error Creating Config Map" + clusterConfigMap.toString() + " on PKS Cluster "
						+ serviceInstanceId);
				client.close();
				LOG.error(e);
				return;
			}
		} else {
			LOG.error("ConfigMapTemplateFile did not contain a valid ConfigMap. Please check  provided file");
			operationStateMessage = "Generated ConfigMap is not a valid a ConfigMap. Contact your Administrator";
			state = OperationState.FAILED;
			client.close();
			return;
		}

		// CREATE ROUTE REG SECRET

		Secret routeRegSecret = client.secrets().load(PKSServiceInstanceAddonDeploymentsRunnable.class.getClassLoader()
				.getResourceAsStream(routeRegSecretFilename)).get();
		routeRegSecret.getData().put("routing_api_client", new String(Base64.encodeBase64(routeClient.getBytes())));
		routeRegSecret.getData().put("routing_api_client_secret",
				new String(Base64.encodeBase64(routeClientSecret.getBytes())));
		if (routeRegSecret instanceof Secret) {
			try {
				client.secrets().createOrReplace(routeRegSecret);
				LOG.info("Create RouteRegSecret for PKS Cluster: " + serviceInstanceId);
			} catch (Exception e) {
				LOG.error("Error Creating Route Registrar Secret " + routeRegSecret.toString() + " on PKS Cluster "
						+ serviceInstanceId);
				LOG.error(e);
				state = OperationState.FAILED;
				client.close();
				return;
			}

		} else {
			LOG.error("RouteRegSecretTemplateFile at : " + routeRegSecretFilename + " did not contain a valid Secret.");
			operationStateMessage = "RouteRegSecret is not valid. Contact your Administrator";
			state = OperationState.FAILED;
			client.close();
			return;
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
			client.close();
			e.printStackTrace();
		}
		// CHECK IF PKS CLUSTER ALREADY HAS A ROUTE EMITTER POD RUNNING
		while (checkRouteRegDeploymentMaster(jsonClusterContext, "master") && !state.equals(OperationState.FAILED)) {
			try {
				TimeUnit.SECONDS.sleep(5);
			} catch (InterruptedException e) {
				client.close();
				e.printStackTrace();
			}
		}
		if (state.equals(OperationState.FAILED)) {
			client.close();
			return;
		}
		operationStateMessage = "RouteRegistration for Master Complete";

		// DEPLOY KIBOSH POD
		try {
			createKiboshDeployment(jsonClusterContext);

		} catch (FileNotFoundException e) {
			client.close();
			e.printStackTrace();
		}

		operationStateMessage = "finished deployment";
		state = OperationState.SUCCEEDED;
		client.close();
	}

	private void applyResources(ArrayList<?> resourceList, JSONObject jsonClusterContext) {
		resourceList.forEach(resourceReference -> {
			InputStream resourceInputStream = null;
			String source = "";
			switch (resourceReference.getClass().getName()) {
			case "java.lang.String":
				try {
					resourceInputStream = new URL(resourceReference.toString()).openStream();
					source = "remote File: " + resourceReference.toString();
				} catch (MalformedURLException e1) {
					resourceInputStream = PKSServiceInstanceAddonDeploymentsRunnable.class.getClassLoader()
							.getResourceAsStream(resourceReference.toString());
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				break;
			case "java.io.BufferedInputStream":
				resourceInputStream = (InputStream) resourceReference;
				source = "Object: " + resourceReference.toString();
				break;
			default:
				System.err.println(resourceReference.getClass().getName());
				break;
			}
			Yaml yamlHandler = new Yaml();
			final String sourceLocation = source;
			yamlHandler.loadAll(resourceInputStream).forEach(resourceObject -> {
				String skipping = "";
				Map<String, String> resource = (HashMap<String, String>) resourceObject;
				InputStream resourceIOStream = null;
				try {
					resourceIOStream = IOUtils.toInputStream(yamlHandler.dump(resourceObject), "UTF-8");
				} catch (IOException e) {
					// TODO Auto-generated catch block
					LOG.error("Error reading ");
					e.printStackTrace();
				}
				switch (resource.get("kind")) {
				case "ClusterRole":
					KubernetesClusterRole kubeClusterRole = client.rbac().kubernetesClusterRoles()
							.load(resourceIOStream).get();
					if (kubeClusterRole instanceof KubernetesClusterRole) {
						String currentNameSpace = kubeClusterRole.getMetadata().getNamespace();
						changeClient(jsonClusterContext, currentNameSpace);
						client.rbac().kubernetesClusterRoles().createOrReplace(kubeClusterRole);
					}
					break;
				case "Namespace":
					Namespace namespace = client.namespaces().load(resourceIOStream).get();
					if (namespace instanceof Namespace) {
						changeClient(jsonClusterContext, "");
						client.namespaces().createOrReplace(namespace);
					}
					break;
				case "ServiceAccount":
					ServiceAccount serviceAccount = client.serviceAccounts().load(resourceIOStream).get();
					if (serviceAccount instanceof ServiceAccount) {
						try {
							String currentNameSpace = serviceAccount.getMetadata().getNamespace();
							changeClient(jsonClusterContext, currentNameSpace);
							if (client.serviceAccounts().withName(serviceAccount.getMetadata().getName()).fromServer()
									.get() != null) {
								serviceAccount = client.serviceAccounts().createOrReplace(serviceAccount);

							} else {
								skipping = "Skipping ";
							}
							LOG.info(skipping + action.toString() + " Kibosh Helm Service Account for PKS Cluster "
									+ serviceInstanceId);
						} catch (KubernetesClientException e) {
							state = OperationState.FAILED;
							LOG.error("Error Creating Service Account " + serviceAccount.toString() + " on PKS Cluster "
									+ serviceInstanceId);
							e.printStackTrace();
							client.close();
							return;
						}
					} else {
						state = OperationState.FAILED;
						client.close();
						LOG.error("Loaded resource is not an Account! " + serviceInstanceId);
						return;
					}

					break;
				case "ClusterRoleBinding":
					KubernetesClusterRoleBinding clusterRoleBinding = client.rbac().kubernetesClusterRoleBindings()
							.load(resourceIOStream).get();
					if (clusterRoleBinding instanceof KubernetesClusterRoleBinding) {
						try {
							String currentNameSpace = clusterRoleBinding.getMetadata().getNamespace();
							changeClient(jsonClusterContext, currentNameSpace);
							client.rbac().kubernetesClusterRoleBindings().createOrReplace(clusterRoleBinding);
							LOG.info(action.toString() + " ClusterRoleBinding "
									+ clusterRoleBinding.getMetadata().getName() + " on PKS Custer "
									+ serviceInstanceId);
							LOG.debug(clusterRoleBinding.toString());
						} catch (KubernetesClientException e) {
							LOG.error("Failed Creating Kibosh Helm Tiller ClusterRoleBinding on PKS Custer "
									+ serviceInstanceId);
							state = OperationState.FAILED;
							e.printStackTrace();
							client.close();
							return;
						}

					} else {
						state = OperationState.FAILED;
						LOG.error("Loaded resource is not a Role Binding! " + serviceInstanceId);
						client.close();
						return;
					}
					break;
				case "StorageClass":
					StorageClass storageClass = client.storage().storageClasses().load(resourceIOStream).get();
					if (storageClass instanceof StorageClass)
						try {
							String currentNameSpace = storageClass.getMetadata().getNamespace();
							changeClient(jsonClusterContext, currentNameSpace);
							if (client.storage().storageClasses().withName(storageClass.getMetadata().getName())
									.fromServer().get() == null)
								client.storage().storageClasses().createOrReplace(storageClass);
							else
								skipping = "Skipping ";

							LOG.info(skipping + action + " of StorageClass for PKS Cluster: " + serviceInstanceId
									+ ". Already found");

						} catch (Exception e) {
							LOG.error("Error Creating Storage Class" + storageClass.toString() + " on PKS Cluster "
									+ serviceInstanceId);
							LOG.error(e);
							state = OperationState.FAILED;
							client.close();
							return;
						}

					break;
				case "Deployment":
					Deployment deployment = client.apps().deployments().load(resourceIOStream).get();
					if (deployment instanceof Deployment) {
						try {
							String currentNameSpace = deployment.getMetadata().getNamespace();
							changeClient(jsonClusterContext, currentNameSpace);
							client.apps().deployments().createOrReplace(deployment);

							LOG.info(action.toString() + " Deployment " + deployment.getMetadata().getName()
									+ " on PKS Cluster " + serviceInstanceId);
						} catch (KubernetesClientException e) {
							state = OperationState.FAILED;
							LOG.error("Error Creating Kibosh Deployment " + deployment.toString() + " on PKS Cluster "
									+ serviceInstanceId);
							client.close();
							e.printStackTrace();
							return;
						}
					} else {
						state = OperationState.FAILED;
						LOG.error("Loaded resource is not a Deployment! " + serviceInstanceId);
						client.close();
						return;
					}

					break;
				case "Service":
					Service service = client.services().load(resourceIOStream).get();
					if (service instanceof Service) {
						try {
							String currentNameSpace = service.getMetadata().getNamespace();
							changeClient(jsonClusterContext, currentNameSpace);
							service = client.services().createOrReplace(service);
							LOG.info(action.toString() + " KIBOSH Service on PKS Custer " + serviceInstanceId);
						} catch (KubernetesClientException e) {
							state = OperationState.FAILED;
							LOG.error("Failed creating KIBOSH Service on PKS Custer " + serviceInstanceId);
							client.close();
							e.printStackTrace();
						}

					} else {
						state = OperationState.FAILED;
						LOG.error("Loaded resource is not a Service! " + serviceInstanceId);
						client.close();
						return;
					}
					break;
				case "ConfigMap":
					ConfigMap configMap = client.configMaps().load(resourceIOStream).get();
					if (configMap instanceof ConfigMap) {
						String currentNameSpace = configMap.getMetadata().getNamespace();
						changeClient(jsonClusterContext, currentNameSpace);
						client.configMaps().createOrReplace(configMap);
					}
					break;

				default:
					LOG.error("Loading of  Resource with type: " + resource.get("type") + " not yet supported "
							+ resource.toString());
					break;
				}
				LOG.info(action + " " + resource.get("kind") + " from " + sourceLocation + " " + resource.toString()
						+ " on PKS Cluster" + serviceInstanceId);

			});
			;
		});

	};

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

	private void setUseExternalRoute(boolean useExternalRoute) {
		this.useExternalRoute = useExternalRoute;
	}

}