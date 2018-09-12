package pksServiceBroker;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
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
import io.fabric8.kubernetes.api.model.rbac.KubernetesRole;
import io.fabric8.kubernetes.api.model.storage.StorageClass;

import org.apache.commons.validator.routines.InetAddressValidator;
import org.json.JSONArray;
import org.json.JSONException;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

	PKSServiceBrokerKubernetesClientUtil clientUtil = new PKSServiceBrokerKubernetesClientUtil();

	private static Logger LOG = LogManager.getLogger(PKSServiceInstanceAddonDeploymentsRunnable.class);

	private String operationStateMessage;
	private OperationState state;
	private int kubeExternalPort = 0;
	private int kiboshExternalPort = 0;
	private int bazaarExternalPort = 0;
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

	private Boolean provisionKibosh = false;
	private Boolean provisionDefaultOperator = false;

	private DefaultKubernetesClient client = new DefaultKubernetesClient();

	@Bean(name = "addonDeploymentRunnable")
	@Scope(value = "prototype")
	public PKSServiceInstanceAddonDeploymentsRunnable getPKSServiceInstanceAddonDeploymentsRunnable(BrokerAction action,
			String serviceInstanceId, String planName, RoutingLayer kiboshRoutingLayer, JSONObject custom_params) {
		PKSServiceInstanceAddonDeploymentsRunnable runner = new PKSServiceInstanceAddonDeploymentsRunnable();
		custom_params.keySet().iterator().forEachRemaining(key -> {
			switch (key) {
			case "provision_kibosh": {
				runner.setProvisionKibosh(custom_params.getBoolean(key));
				LOG.info("provision kibosh set to " + runner.provisionKibosh.toString());
				break;
			}
			case "provision_default_operator": {
				runner.setProvisionDefaultOperator(custom_params.getBoolean(key));
				LOG.info("provision default operator set to " + runner.provisionDefaultOperator.toString());
				break;
			}
			default:
				break;
			}
		});

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
				client = clientUtil.changeClient(jsonClusterContext, "kube-system");
				LOG.info("ConfigMap with ClusterData found on PKS Cluster " + runner.serviceInstanceId);
				runner.setClusterConfigMap(
						client.configMaps().withName("cluster-addon-deployment-data").fromServer().get());
				if (runner.provisionKibosh) {
					addKiboshDataToMap(runner);
				} else {
					removeKiboshDataFromMap(runner);
				}
			} catch (Exception e) {
				e.printStackTrace();
				if (runner.kiboshRoutingLayer.equals(RoutingLayer.TCP)) {
					runner.kiboshExternalPort = getFreePortForCFTcpRouter();
					runner.bazaarExternalPort = getFreePortForCFTcpRouter();
				}
				createConfigMap(runner);
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
			createConfigMap(runner);
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
				client.close();
				client = clientUtil.changeClient(jsonClusterContext, "kube-system");
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

	private void createConfigMap(PKSServiceInstanceAddonDeploymentsRunnable runner) {
		LOG.info("Config Map not found on PKS Cluster " + runner.serviceInstanceId + ". Creating...");
		runner.clusterConfigMap = runner.client.configMaps().load(PKSServiceInstanceAddonDeploymentsRunnable.class
				.getClassLoader().getResourceAsStream(Config.clusterConfigMapFilename)).get();

		if (runner.kiboshRoutingLayer.equals(RoutingLayer.TCP) && provisionKibosh) {
			runner.kiboshExternalPort = getFreePortForCFTcpRouter();
			runner.bazaarExternalPort = getFreePortForCFTcpRouter();
			LOG.debug("Reserving Kibosh Port " + runner.kiboshExternalPort + "; Bazaar Port: "
					+ runner.bazaarExternalPort);
		}
		if (runner.provisionKibosh) {
			addKiboshDataToMap(runner);
		} else {
			removeKiboshDataFromMap(runner);
		}
		runner.clusterConfigMap.getData().put("kubernetes.fqdn", sbConfig.TCP_FQDN);
		runner.clusterConfigMap.getData().put("kubernetes.port", Integer.toString(runner.kubeExternalPort));
		runner.clusterConfigMap.getData().put("kubernetes.protocol", "https://");
	}

	private void addKiboshDataToMap(PKSServiceInstanceAddonDeploymentsRunnable runner) {
		LOG.trace("PKS Cluster: " + runner.serviceInstanceId
				+ " Kibosh set to true, adding related ClusterConfig Data from Map");
		String prefix = runner.kiboshRoutingLayer.equals(RoutingLayer.HTTP) ? "https://" : "http://";

		runner.clusterConfigMap.getData().put("kibosh.fqdn",
				getComponentHostname(pksServiceBroker.Config.KIBOSH_NAME, runner));

		runner.clusterConfigMap.getData().put("kibosh.port",
				runner.kiboshExternalPort == 0 ? "443" : Integer.toString(runner.kiboshExternalPort));

		if (!runner.clusterConfigMap.getData().containsKey("kibosh.user")
				|| runner.clusterConfigMap.getData().get("kibosh.user") == null)
			runner.clusterConfigMap.getData().put("kibosh.user", java.util.UUID.randomUUID().toString());
		if (!runner.clusterConfigMap.getData().containsKey("kibosh.password")
				| runner.clusterConfigMap.getData().get("kibosh.password") == null)
			runner.clusterConfigMap.getData().put("kibosh.password", java.util.UUID.randomUUID().toString());
		runner.clusterConfigMap.getData().put("kibosh.protocol", prefix);

		runner.clusterConfigMap.getData().put("bazaar.fqdn",
				getComponentHostname(pksServiceBroker.Config.BAZAAR_NAME, runner));

		if (!runner.clusterConfigMap.getData().containsKey("bazaar.user")
				|| runner.clusterConfigMap.getData().get("bazaar.user") == null)
			runner.clusterConfigMap.getData().put("bazaar.user", java.util.UUID.randomUUID().toString());

		if (!runner.clusterConfigMap.getData().containsKey("bazaar.password")
				|| runner.clusterConfigMap.getData().get("bazaar.password") == null)
			runner.clusterConfigMap.getData().put("bazaar.password", java.util.UUID.randomUUID().toString());

		runner.clusterConfigMap.getData().put("bazaar.port",
				bazaarExternalPort == 0 ? "443" : Integer.toString(bazaarExternalPort));

		runner.clusterConfigMap.getData().put("bazaar.protocol", prefix);
	}

	private void removeKiboshDataFromMap(PKSServiceInstanceAddonDeploymentsRunnable runner) {
		LOG.trace("PKS Cluster: " + runner.serviceInstanceId
				+ " Kibosh set to false, removing related ClusterConfig Data from Map");
		if (runner.clusterConfigMap.getData().containsKey("kibosh.fqdn"))
			runner.clusterConfigMap.getData().remove("kibosh.fqdn");
		if (runner.clusterConfigMap.getData().containsKey("kibosh.port"))
			runner.clusterConfigMap.getData().remove("kibosh.port");
		if (runner.clusterConfigMap.getData().containsKey("kibosh.user"))
			runner.clusterConfigMap.getData().remove("kibosh.user");
		if (runner.clusterConfigMap.getData().containsKey("kibosh.password"))
			runner.clusterConfigMap.getData().remove("kibosh.password");
		if (runner.clusterConfigMap.getData().containsKey("kibosh.protocol"))
			runner.clusterConfigMap.getData().remove("kibosh.protocol");
		if (runner.clusterConfigMap.getData().containsKey("bazaar.fqdn"))
			runner.clusterConfigMap.getData().remove("bazaar.fqdn");
		if (runner.clusterConfigMap.getData().containsKey("bazaar.user"))
			runner.clusterConfigMap.getData().remove("bazaar.user");
		if (runner.clusterConfigMap.getData().containsKey("bazaar.password"))
			runner.clusterConfigMap.getData().remove("bazaar.password");
		if (runner.clusterConfigMap.getData().containsKey("bazaar.port"))
			runner.clusterConfigMap.getData().remove("bazaar.port");
		if (runner.clusterConfigMap.getData().containsKey("bazaar.protocol"))
			runner.clusterConfigMap.getData().remove("bazaar.protocol");
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
		client = clientUtil.changeClient(kubeConfig, "");
		int numberNamespaces = client.namespaces().list().getItems().size();
		if (numberNamespaces < 1) {
			state = OperationState.FAILED;
			client.close();
			cont = false;
		} else {
			LOG.trace("PKS Cluster: " + serviceInstanceId + " Found " + numberNamespaces
					+ " via internal route; Changing to external");
			clientUtil.setUseExternalRoute(true);
			client = clientUtil.changeClient(kubeConfig, "");
			try {
				if (client.namespaces().list().getItems().size() > 0) {
					cont = false;
					LOG.info("Master Routes active on PKS Cluster " + serviceInstanceId);
					clientUtil.setUseExternalRoute(true);
					operationStateMessage = "Master Routes active";
				}
			} catch (KubernetesClientException e) {
				// our try used external url, so we use Exception to figure out whether route is
				// ready
				LOG.info("Waiting for Master Routes to become active on PKS Cluster " + serviceInstanceId);
				operationStateMessage = "Waiting for Master Routes to become active";
			} catch (Exception e) {
				client.close();
				e.printStackTrace();
			}
		}
		client.close();
		return cont;
	}

	public Deployment createRouteRegDeploymentResource(JSONObject kubeConfig, int internalPort,
			List<Object> internalIPs, int externalPort, String componentName, RoutingLayer routingLayer)
			throws FileNotFoundException {
		String deploymentName = pksServiceBroker.Config.ROUTE_DEPLOYMENT_PREFIX + componentName;

		Deployment routeEmitDeployment = client.apps().deployments()
				.load(PKSServiceInstanceAddonDeploymentsRunnable.class.getClassLoader()
						.getResourceAsStream(Config.routeEmitDeploymentFilename))
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
			LOG.info("Succesfully Loaded Deployment Object for RouteRegistration of " + componentName
					+ " on PKS Cluster " + serviceInstanceId);
			client.close();
			return routeEmitDeployment;
		} else {
			LOG.error("Loaded resource is not a Deployment! Could not create RouteReg Deployment for " + componentName
					+ " on PKS Cluster " + serviceInstanceId);
		}
		client.close();
		return null;

	}

	protected void applyKiboshDeployment(JSONObject jsonClusterContext) throws FileNotFoundException {

		ArrayList<Object> typedResourceList = createTypedResources(sbConfig.getDefaultAddons(), jsonClusterContext);

		JSONArray nodeIPs = new JSONArray();
		client.nodes().list().getItems().forEach((Node node) -> {
			nodeIPs.put(node.getStatus().getAddresses().get(0).getAddress());
		});

		// First run, apply static resources
		if (provisionKibosh)
			applyTypedResource(typedResourceList, "CREATE");
		else
			applyTypedResource(typedResourceList, "DELETE");

		LOG.debug("Will use NodeIPs: " + nodeIPs.toString() + " for route Registration on PKS Cluster: "
				+ serviceInstanceId);
		client = clientUtil.changeClient(jsonClusterContext, "kube-system");

		typedResourceList = new ArrayList<>();
		typedResourceList.add(createRouteRegDeploymentResource(jsonClusterContext, provisionKibosh
				? client.services().withName("kibosh-np").fromServer().get().getSpec().getPorts().get(0).getNodePort()
				: 0, nodeIPs.toList(), kiboshExternalPort, pksServiceBroker.Config.KIBOSH_NAME, kiboshRoutingLayer));

		typedResourceList.add(createRouteRegDeploymentResource(jsonClusterContext,
				provisionKibosh
						? client.services().withName("kibosh-bazaar-np").fromServer().get().getSpec().getPorts().get(0)
								.getNodePort()
						: 0,
				nodeIPs.toList(), bazaarExternalPort, pksServiceBroker.Config.BAZAAR_NAME, kiboshRoutingLayer));

		// second run apply dynamic resources
		if (provisionKibosh)
			applyTypedResource(typedResourceList, "CREATE");
		else
			applyTypedResource(typedResourceList, "DELETE");
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

		// GET CLUSTER CONTEXT
		LOG.debug("Getting credentials for " + serviceInstanceId);
		JSONObject jsonClusterContext = new JSONObject(pksRestTemplate.postForObject(
				"https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId + "/binds", pksRequestObject,
				String.class));

		jsonClusterContext.put("master_ip", master_ips.getString(0));

		LOG.info("Applying Default Addons to : " + serviceInstanceId);

		// GET CLUSTER INFO
		jsonClusterInfo = new JSONObject(pksRestTemplate
				.getForObject("https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId, String.class));

		ArrayList<Object> typedResourceList = new ArrayList<Object>();
		client = clientUtil.changeClient(jsonClusterContext, "kube-system");
		// CREATE CONFIGMAP
		if (clusterConfigMap instanceof ConfigMap) {
			typedResourceList.add(clusterConfigMap);
		} else {
			LOG.error("ConfigMapTemplateFile did not contain a valid ConfigMap. Please check  provided file");
			operationStateMessage = "Generated ConfigMap is not a valid a ConfigMap. Contact your Administrator";
			state = OperationState.FAILED;
			client.close();
			return;
		}

		// CREATE ROUTE REG SECRET
		Secret routeRegSecret = client.secrets().load(PKSServiceInstanceAddonDeploymentsRunnable.class.getClassLoader()
				.getResourceAsStream(Config.routeRegSecretFilename)).get();
		routeRegSecret.getData().put("routing_api_client", new String(Base64.encodeBase64(routeClient.getBytes())));
		routeRegSecret.getData().put("routing_api_client_secret",
				new String(Base64.encodeBase64(routeClientSecret.getBytes())));
		if (routeRegSecret instanceof Secret) {
			typedResourceList.add(routeRegSecret);

		} else {
			LOG.error("RouteRegSecretTemplateFile at : " + Config.routeRegSecretFilename
					+ " did not contain a valid Secret.");
			operationStateMessage = "RouteRegSecret is not valid. Contact your Administrator";
			state = OperationState.FAILED;
			client.close();
			return;
		}
		// CREATE ROUTE REG DEPLOYMENT FOR MASTER
		Deployment routeRegDeployment;
		try {
			routeRegDeployment = createRouteRegDeploymentResource(jsonClusterContext,
					pksServiceBroker.Config.KUBERNETES_MASTER_PORT,
					jsonClusterInfo.getJSONArray("kubernetes_master_ips").toList(),
					jsonClusterInfo.getJSONObject("parameters").getInt("kubernetes_master_port"), "master",
					RoutingLayer.TCP);
			if (routeRegDeployment instanceof Deployment) {
				typedResourceList.add(routeRegDeployment);
			} else {

			}
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		} catch (JSONException e1) {
			e1.printStackTrace();
		}

		updateLastOperationConfigMap(state, action, operationStateMessage);
		applyTypedResource(typedResourceList, "CREATE");

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
		updateLastOperationConfigMap(state, action, operationStateMessage);

		if (provisionDefaultOperator) {
			LOG.info("Applying Default Operators to : " + serviceInstanceId);
			LOG.trace("Operators: " + sbConfig.getDefaultOperators());
			operationStateMessage = "Applying Default Operators";
			updateLastOperationConfigMap(state, action, operationStateMessage);
			ArrayList<Object> defaultOperators = createTypedResources(sbConfig.getDefaultOperators(),
					jsonClusterContext);
			try {
				applyTypedResource(defaultOperators, "CREATE");
			} catch (Exception e) {
				client.close();
				state = OperationState.FAILED;
				operationStateMessage = "Failed applying default Operators";
				updateLastOperationConfigMap(state, action, operationStateMessage);
				e.printStackTrace();
			}
		}

		try {
			applyKiboshDeployment(jsonClusterContext);
		} catch (FileNotFoundException e) {
			client.close();
			state = OperationState.FAILED;
			operationStateMessage = "Failed applying Kibosh";
			updateLastOperationConfigMap(state, action, operationStateMessage);
			e.printStackTrace();
		}

		operationStateMessage = "finished deployment";
		state = OperationState.SUCCEEDED;
		updateLastOperationConfigMap(state, action, operationStateMessage);
		client.close();
		LOG.info("Finished addon Deployments on PKS Cluster: " + serviceInstanceId);
	}

	private ConfigMap updateLastOperationConfigMap(OperationState state, BrokerAction action,
			String operationStateMessage) {
		ConfigMap lastOpConfigMap = client.configMaps().load(PKSServiceInstanceLastOperationInfo.class.getClassLoader()
				.getResourceAsStream(Config.lastOpConfigMapFilename)).get();
		lastOpConfigMap = client.configMaps().inNamespace(lastOpConfigMap.getMetadata().getNamespace())
				.withName(lastOpConfigMap.getMetadata().getName()).edit()
					.addToData("state", state.name())
					.addToData("action", action.name())
					.addToData("message", operationStateMessage)
				.done();

		LOG.info("Updating Last Operation Config Map on PKS Cluster " + serviceInstanceId);
		return lastOpConfigMap;
	}

	private void applyTypedResource(ArrayList<Object> typedResourceList, String applyAction) {
		typedResourceList.forEach(resource -> {
			String skipping = "";
			if (resource != null)
				switch (resource.getClass().getName()) {
				case "io.fabric8.kubernetes.api.model.Service":
					Service service = (Service) resource;
					try {
						if (applyAction.equals("DELETE"))
							client.services().delete(service);
						else
							service = client.services().createOrReplace(service);
						LOG.info(applyAction + " Service " + service.getMetadata().getName() + " on PKS Custer "
								+ serviceInstanceId);
					} catch (KubernetesClientException e) {
						state = OperationState.FAILED;
						LOG.error("Error in " + applyAction + " Service " + service.getMetadata().getName()
								+ " on PKS Custer " + serviceInstanceId);
						client.close();
						e.printStackTrace();
						return;
					}
					break;
				case "io.fabric8.kubernetes.api.model.Namespace":
					Namespace namespace = (Namespace) resource;
					try {
						if (applyAction.equals("DELETE"))
							client.namespaces().withName(namespace.getMetadata().getName()).delete();
						else
							namespace = client.namespaces().withName(namespace.getMetadata().getName()).fromServer()
									.get() == null ? client.namespaces().createOrReplace(namespace)
											: client.namespaces().withName(namespace.getMetadata().getName())
													.fromServer().get();
					} catch (Exception e) {
						client.namespaces().withName(namespace.getMetadata().getName()).fromServer().get();
					}
					break;
				case "io.fabric8.kubernetes.api.model.apps.Deployment":
					Deployment deployment = (Deployment) resource;
					try {
						if (applyAction.equals("DELETE"))
							client.apps().deployments().delete(deployment);
						else
							client.apps().deployments().createOrReplace(deployment);
						LOG.info(applyAction + " Deployment " + deployment.getMetadata().getName() + " on PKS Cluster "
								+ serviceInstanceId);
					} catch (KubernetesClientException e) {
						state = OperationState.FAILED;
						LOG.error("Error in " + applyAction + " Deployment " + deployment.toString()
								+ " on PKS Cluster " + serviceInstanceId);
						client.close();
						e.printStackTrace();
						return;
					}
					break;
				case "io.fabric8.kubernetes.api.model.storage.StorageClass":
					StorageClass storageClass = (StorageClass) resource;
					try {
						if (client.storage().storageClasses().withName(storageClass.getMetadata().getName())
								.fromServer().get() == null)
							if (applyAction.equals("DELETE"))
								;
							else
								client.storage().storageClasses().createOrReplace(storageClass);
						else
							skipping = "Skipping ";

						LOG.info(skipping + applyAction + " of StorageClass for PKS Cluster: " + serviceInstanceId);

					} catch (Exception e) {
						LOG.error("Error in " + applyAction + " Storage Class" + storageClass.toString()
								+ " on PKS Cluster " + serviceInstanceId);
						LOG.error(e);
						state = OperationState.FAILED;
						client.close();
						return;
					}
					break;
				case "io.fabric8.kubernetes.api.model.rbac.KubernetesClusterRoleBinding":
					KubernetesClusterRoleBinding clusterRoleBinding = (KubernetesClusterRoleBinding) resource;
					try {
						if (applyAction.equals("DELETE"))
							client.rbac().kubernetesClusterRoleBindings().delete(clusterRoleBinding);
						else
							client.rbac().kubernetesClusterRoleBindings().createOrReplace(clusterRoleBinding);
						LOG.info(applyAction + " ClusterRoleBinding " + clusterRoleBinding.getMetadata().getName()
								+ " on PKS Custer " + serviceInstanceId);
						LOG.debug(clusterRoleBinding.toString());
					} catch (KubernetesClientException e) {
						LOG.error("Error in " + applyAction + " Kibosh Helm Tiller ClusterRoleBinding on PKS Custer "
								+ serviceInstanceId);
						state = OperationState.FAILED;
						e.printStackTrace();
						client.close();
						return;
					}
					break;
				case "io.fabric8.kubernetes.api.model.rbac.KubernetesClusterRole":
					KubernetesClusterRole clusterRole = (KubernetesClusterRole) resource;
					clusterRole = client.rbac().kubernetesClusterRoles().createOrReplace(clusterRole);
					break;
				case "io.fabric8.kubernetes.api.model.ConfigMap":
					ConfigMap configMap = (ConfigMap) resource;
					try {
						if (applyAction.equals("DELETE"))
							client.configMaps().delete(clusterConfigMap);
						else
							configMap = client.configMaps().createOrReplace(clusterConfigMap);
						LOG.info(action.toString() + " Cluster ConfigMap for PKS Cluster " + serviceInstanceId);
					} catch (KubernetesClientException e) {
						state = OperationState.FAILED;
						LOG.error("Error " + applyAction + "  Config Map" + configMap.toString() + " on PKS Cluster "
								+ serviceInstanceId);
						client.close();
						LOG.error(e);
						return;
					}
					break;
				case "io.fabric8.kubernetes.api.model.Secret":
					Secret secret = (Secret) resource;
					try {
						if (applyAction.equals("DELETE"))
							client.secrets().delete(secret);
						else
							secret = client.secrets().createOrReplace(secret);
						LOG.info("Create RouteRegSecret for PKS Cluster: " + serviceInstanceId);
					} catch (Exception e) {
						LOG.error("Error " + applyAction + "  Route Registrar Secret " + secret.toString()
								+ " on PKS Cluster " + serviceInstanceId);
						LOG.error(e);
						state = OperationState.FAILED;
						client.close();
						return;
					}
					break;
				case "io.fabric8.kubernetes.api.model.ServiceAccount":
					ServiceAccount serviceAccount = (ServiceAccount) resource;
					try {
						if (client.serviceAccounts().withName(serviceAccount.getMetadata().getName()).fromServer()
								.get() == null) {
							if (applyAction.equals("DELETE"))
								client.serviceAccounts().delete(serviceAccount);
							else
								serviceAccount = client.serviceAccounts().createOrReplace(serviceAccount);

						} else {
							skipping = "Skipping ";
						}
						LOG.info(skipping + action.toString() + " Kibosh Helm Service Account for PKS Cluster "
								+ serviceInstanceId);
					} catch (KubernetesClientException e) {
						state = OperationState.FAILED;
						LOG.error("Error " + applyAction + "  Service Account " + serviceAccount.toString()
								+ " on PKS Cluster " + serviceInstanceId);
						e.printStackTrace();
						client.close();
					}
					break;
				default:
					break;
				}
		});

	}

	private ArrayList<Object> createTypedResources(ArrayList<?> resourceList, JSONObject jsonClusterContext) {
		ArrayList<Object> typedResources = new ArrayList<>();
		resourceList.forEach(resourceReference -> {
			InputStream resourceInputStream = null;
			String source = "";
			switch (resourceReference.getClass().getName()) {
			case "java.lang.String":
				try {
					resourceInputStream = new URL(resourceReference.toString()).openStream();
					source = "remote File: " + resourceReference.toString();

				} catch (MalformedURLException e1) {
					source = "Classpath File: " + resourceReference.toString();
					resourceInputStream = PKSServiceInstanceAddonDeploymentsRunnable.class.getClassLoader()
							.getResourceAsStream(resourceReference.toString());
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				break;
			case "java.io.BufferedInputStream":
				resourceInputStream = (InputStream) resourceReference;
				source = "Object: " + resourceReference.toString();
				break;
			default:
				break;
			}
			LOG.trace(source);
			final String sourceLocation = source;
			Yaml yamlHandler = new Yaml();
			Iterable<Object> resourceIterable = yamlHandler.loadAll(resourceInputStream);
			while (resourceIterable.iterator().hasNext()) {
				Object resourceObject = resourceIterable.iterator().next();
				if (resourceObject != null) {
					try {
						typedResources.add(
								loadResource(castObjectToHashMap(resourceObject), jsonClusterContext, sourceLocation));
					} catch (NoSuchMethodException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (SecurityException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (IllegalAccessException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (IllegalArgumentException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (InvocationTargetException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		});
		return typedResources;
	}

	private HashMap<String, String> castObjectToHashMap(Object resourceObject) throws NoSuchMethodException,
			SecurityException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		Method method = resourceObject.getClass().getMethod("putAll", Map.class);
		HashMap<String, String> castedObject = new HashMap<>();
		method.invoke(castedObject, resourceObject);
		LOG.trace(
				"PKS Cluster: " + serviceInstanceId + " Succesfully casted " + resourceObject + " to " + castedObject);
		return castedObject;
	}

	private Object loadResource(HashMap<String, String> hashMap, JSONObject jsonClusterContext, String sourceLocation) {
		// TODO Auto-generated method stub
		Yaml yamlHandler = new Yaml();
		JSONObject resourceJSON = new JSONObject(hashMap);
		InputStream resourceIOStream = null;
		LOG.trace("PKS Cluster: " + serviceInstanceId + " Loaded resource object: " + hashMap.toString());
		LOG.trace("PKS Cluster: " + serviceInstanceId + " created JSON: " + resourceJSON.toString());
		try {
			resourceIOStream = IOUtils.toInputStream(yamlHandler.dump(hashMap), "UTF-8");
		} catch (IOException e) {
			LOG.error("Error loading " + hashMap.toString());
			e.printStackTrace();
		}
		String currentNamespace = "";
		try {

			LOG.trace("Trying to load metadata from: " + resourceJSON.toString());
			currentNamespace = resourceJSON.getJSONObject("metadata").getString("namespace");
		} catch (Exception e2) {
			// This most probably means that the resource does not use or provide a
			// Namespace. This can also be caused by a bad config provided in the yaml
		}
		client = clientUtil.changeClient(jsonClusterContext, currentNamespace);
		switch (resourceJSON.getString("kind")) {
		case "Role":
			KubernetesRole kubeRole = client.rbac().kubernetesRoles().load(resourceIOStream).get();

			if (kubeRole instanceof KubernetesRole)
				return kubeRole;

			LOG.error("Loaded resource is not KubernetesRole! PKS Cluster: " + serviceInstanceId + " Resource: "
					+ resourceJSON.toString());

			break;
		case "ClusterRole":
			KubernetesClusterRole kubeClusterRole = client.rbac().kubernetesClusterRoles().load(resourceIOStream).get();

			if (kubeClusterRole instanceof KubernetesClusterRole)
				return kubeClusterRole;

			LOG.error("Loaded resource is not KubernetesClusterRole! PKS Cluster: " + serviceInstanceId + " Resource: "
					+ resourceJSON.toString());

			break;
		case "Namespace":
			Namespace namespace = client.namespaces().load(resourceIOStream).get();
			if (namespace instanceof Namespace)
				return namespace;
			state = OperationState.FAILED;
			client.close();
			LOG.error("Loaded resource is not Namespace! PKS Cluster: " + serviceInstanceId + " Resource: "
					+ resourceJSON.toString());
			break;
		case "ServiceAccount":
			ServiceAccount serviceAccount = client.serviceAccounts().load(resourceIOStream).get();
			if (serviceAccount instanceof ServiceAccount)
				return serviceAccount;
			state = OperationState.FAILED;
			client.close();
			LOG.error("Loaded resource is not an Account! PKS Cluster: " + serviceInstanceId + " Resource: "
					+ resourceJSON.toString());
			break;
		case "ClusterRoleBinding":
			KubernetesClusterRoleBinding clusterRoleBinding = client.rbac().kubernetesClusterRoleBindings()
					.load(resourceIOStream).get();
			if (clusterRoleBinding instanceof KubernetesClusterRoleBinding)
				return clusterRoleBinding;
			state = OperationState.FAILED;
			LOG.error("Loaded resource is not a Role Binding! PKS Cluster: " + serviceInstanceId + " Resource: "
					+ resourceJSON.toString());
			client.close();
			break;
		case "StorageClass":
			StorageClass storageClass = client.storage().storageClasses().load(resourceIOStream).get();
			if (storageClass instanceof StorageClass)
				return storageClass;
			state = OperationState.FAILED;
			LOG.error("Loaded resource is not a StorageClass! PKS Cluster: " + serviceInstanceId + "Resource: "
					+ resourceJSON.toString());
			client.close();
			break;
		case "Deployment":
			Deployment deployment = client.apps().deployments().load(resourceIOStream).get();
			if (deployment instanceof Deployment)
				return deployment;
			state = OperationState.FAILED;
			LOG.error("Loaded resource is not a Deployment! PKS Cluster: " + serviceInstanceId + "Resource: "
					+ resourceJSON.toString());
			client.close();
			break;
		case "Service":
			Service service = client.services().load(resourceIOStream).get();
			if (service instanceof Service)
				return service;

			state = OperationState.FAILED;
			LOG.error("Loaded resource is not a Service! PKS Cluster: " + serviceInstanceId + "Resource: "
					+ resourceJSON.toString());
			client.close();
			break;
		case "ConfigMap":
			ConfigMap configMap = client.configMaps().load(resourceIOStream).get();
			if (configMap instanceof ConfigMap)
				return configMap;
			state = OperationState.FAILED;
			LOG.error("Loaded resource is not a Service! PKS Cluster: " + serviceInstanceId + "Resource: "
					+ resourceJSON.toString());
			client.close();
			break;

		default:
			LOG.error("PKS Cluster: " + serviceInstanceId + " Loading of  Resource not yet supported "
					+ resourceJSON.toString());
			return null;
		}
		LOG.info(action + " " + resourceJSON.get("kind") + " from " + sourceLocation + " " + resourceJSON.toString()
				+ " on PKS Cluster" + serviceInstanceId);

		client.close();
		return null;
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

	public void setProvisionKibosh(Boolean provisionKibosh) {
		this.provisionKibosh = provisionKibosh;
	}

	private void setProvisionDefaultOperator(Boolean provisionDefaultOperator) {
		this.provisionDefaultOperator = provisionDefaultOperator;

	}

}