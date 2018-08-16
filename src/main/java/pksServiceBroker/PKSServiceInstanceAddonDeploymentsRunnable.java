package pksServiceBroker;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.extensions.Deployment;

import org.apache.commons.validator.routines.InetAddressValidator;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.servicebroker.model.catalog.Plan;
import org.springframework.cloud.servicebroker.model.catalog.ServiceDefinition;
import org.springframework.cloud.servicebroker.model.instance.CreateServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.OperationState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.io.FileNotFoundException;
import java.util.logging.Logger;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "routereg")
public class PKSServiceInstanceAddonDeploymentsRunnable implements Runnable {

	@Value("${pcf.tcp}")
	private String appsTcpFqdn;

	@Value("${pcf.api}")
	private String pcfApi;

	@Value("${routereg.route_api_client.client_id}")
	private String routeClient;

	@Value("${routereg.route_api_client.client_secret}")
	private String routeClientSecret;

	@Value("${routereg.externalPortRange}")
	private String portRange;

	@Value("${pks.fqdn}")
	private String PKS_FQDN;
	@Value("${pcf.tcp}")
	private String TCP_FQDN;

	private static String routeEmitDeploymentFilename = "config/route-reg-deployment.yaml";
	private static String kiboshDeploymentFilename = "config/kibosh-deployment.yaml";
	private static String kiboshRBACFilename = "config/kibosh-rbac.yaml";
	private static String kiboshServiceFilename = "config/kibosh-service.yaml";
	private static String bazaarServiceFilename = "config/kibosh-bazaar-service.yaml";

	private static String namespace = "kube-system";
	private static int kubePort = 8443;
	private static final Logger LOG = Logger.getLogger(PKSServiceInstanceAddonDeploymentsRunnable.class.getName());

	private static final String ROUTE_DEPLOYMENT_PREFIX = "tcp-route-registrar-";
	private static int routeTTL = 20;
	private String operationStateMessage;
	private OperationState state;
	private int externalPort;
	private CreateServiceInstanceRequest serviceInstanceRequest;
	private String action;
	private String pksPlanName;

	public String getPksPlanName() {
		return pksPlanName;
	}

	@Autowired
	@Qualifier("pks")
	OAuth2RestTemplate pksRestTemplate;

	@Autowired
	@Qualifier("route")
	OAuth2RestTemplate routeRestTemplate;

	// CONSTRUCTOR BLOCK
	@Bean(name = "addonDeploymentRunnable")
	@Scope(value = "prototype")
	public PKSServiceInstanceAddonDeploymentsRunnable getPKSServiceInstanceAddonDeploymentsRunnable(String action,
			CreateServiceInstanceRequest request) {
		PKSServiceInstanceAddonDeploymentsRunnable runner = new PKSServiceInstanceAddonDeploymentsRunnable();
		runner.setRequest(request);
		runner.setAction(action);
		runner.setExternalPort(getFreePortForCFTcpRouter());
		return runner;
	}

	private void setExternalPort(int externalPort) {
		this.externalPort = externalPort;
	}

	private void setRequest(CreateServiceInstanceRequest serviceInstanceRequest) {
		this.serviceInstanceRequest = serviceInstanceRequest;
	}

	public PKSServiceInstanceAddonDeploymentsRunnable() {
		this.state = OperationState.IN_PROGRESS;
		this.operationStateMessage = "Started Deployment";
	}

	// GETTER & SETTER Block
	public int getExternalPort() {
		return externalPort;
	}

	private KubernetesClient getClient(JSONObject kubeConfig, boolean internalRoute) {
		String masterURL = "";
		if (internalRoute) {
			masterURL = "https://" + kubeConfig.getString("master_ip") + ":" + kubePort + "/";
		} else {
			masterURL = kubeConfig.getJSONArray("clusters").getJSONObject(0).getJSONObject("cluster")
					.getString("server");
		}
		Config config = new ConfigBuilder().withMasterUrl(masterURL)
				.withOauthToken(
						kubeConfig.getJSONArray("users").getJSONObject(0).getJSONObject("user").getString("token"))
				.withCaCertData(kubeConfig.getJSONArray("clusters").getJSONObject(0).getJSONObject("cluster")
						.getString("certificate-authority-data"))
				.withNamespace(namespace).withTrustCerts(true).build();
		KubernetesClient client = new DefaultKubernetesClient(config);

		return client;
	}


	protected int getFreePortForCFTcpRouter() {
		int portMin = Integer.parseInt(portRange.split("-")[0]);
		int portMax = Integer.parseInt(portRange.split("-")[1]);
		int portMaster = new Random().nextInt(portMax - portMin + 1) + portMin;
		Set<Integer> ports = new LinkedHashSet<>();
		HttpHeaders headers = new HttpHeaders();
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + routeRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		HttpEntity<String> requestObject = new HttpEntity<String>("", headers);
		JSONArray routes = new JSONArray(routeRestTemplate
				.exchange("https://" + pcfApi + "/routing/v1/tcp_routes", HttpMethod.GET, requestObject, String.class)
				.getBody().toString());
		routes.forEach((route) -> {
			ports.add(new JSONObject(route.toString()).getInt("port"));
		});
		while (ports.contains(portMaster))
			portMaster = new Random().nextInt(portMax - portMin + 1) + portMin;
		return portMaster;
	}

	protected Boolean checkRouteRegDeployment(JSONObject kubeConfig, String componentName) {
		KubernetesClient client = getClient(kubeConfig, true);
		String deploymentName= ROUTE_DEPLOYMENT_PREFIX + componentName;
		Deployment deployment = client.extensions().deployments().withName(deploymentName).get();
		if (deployment == null) {
			client.close();
			System.err.println("did not find deployment"+deploymentName);
			return false;
		} else {
			try {
				client = getClient(kubeConfig, false);
				if (client != null && client.getApiVersion() != null) {
					System.err.println("deployment working");
					return true;
				} else {
					System.err.println("deployment found but not operational");
					return false;
				}
			} catch (Exception e) {
				LOG.info("Route not yet available");
			}
			client.close();
		}
		return false;
	}
	public void createRouteRegDeployment(JSONObject kubeConfig, int internalPort, List<Object> internalIPs,
			int externalPort, String componentName) throws FileNotFoundException {
		String deploymentName = ROUTE_DEPLOYMENT_PREFIX + componentName;
		KubernetesClient client = getClient(kubeConfig, true);

		List<HasMetadata> routeEmitDeployment = client.load(PKSServiceInstanceAddonDeploymentsRunnable.class
				.getClassLoader().getResourceAsStream(routeEmitDeploymentFilename)).get();

		if (routeEmitDeployment.isEmpty()) {
			LOG.severe("No resources loaded from file: " + routeEmitDeploymentFilename);
		}
		Deployment deployment = (Deployment) routeEmitDeployment.get(0);
		if (deployment instanceof Deployment) {
			deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().iterator()
					.forEachRemaining((EnvVar envVar) -> {
						switch (envVar.getName()) {
						case "CF_API_FQDN": {
							envVar.setValue(pcfApi);
							break;
						}
						case "TCP_ROUTER_PORT": {
							envVar.setValue(Integer.toString(externalPort));
							break;
						}
						case "SERVICE_ID": {
							envVar.setValue(kubeConfig.getString("current-context"));
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
							envVar.setValue(Integer.toString(routeTTL));
							break;
						}
						}
					});
			deployment.getMetadata().setName(deploymentName);
			deployment.getSpec().getTemplate().getMetadata().getLabels().put("name", deploymentName);
			deployment.getSpec().getTemplate().getSpec().getContainers().get(0).setName(deploymentName);
			deployment = client.extensions().deployments().createOrReplace(deployment);
			LOG.info("Created Deployment " + deployment.getStatus());

		} else {
			LOG.severe("Loaded resource is not a Deployment! " + deployment);
		}
		client.close();
	}

	protected void createKiboshDeployment(JSONObject kubeConfig) throws FileNotFoundException {
		KubernetesClient client = getClient(kubeConfig, false);

		Deployment kiboshDeployment = (Deployment) client.load(PKSServiceInstanceAddonDeploymentsRunnable.class
				.getClassLoader().getResourceAsStream(kiboshDeploymentFilename)).get().get(0);
		Service kiboshBazaarService = (Service) client.load(PKSServiceInstanceAddonDeploymentsRunnable.class
				.getClassLoader().getResourceAsStream(bazaarServiceFilename)).get().get(0);
		Service kiboshService = (Service) client.load(PKSServiceInstanceAddonDeploymentsRunnable.class.getClassLoader()
				.getResourceAsStream(kiboshServiceFilename)).get().get(0);
		ServiceAccount kiboshRBAC = (ServiceAccount) client.load(PKSServiceInstanceAddonDeploymentsRunnable.class
				.getClassLoader().getResourceAsStream(kiboshRBACFilename)).get().get(0);

		if (kiboshDeployment instanceof Deployment) {
			kiboshDeployment.getSpec().getTemplate().getSpec().getContainers();
			kiboshDeployment = client.extensions().deployments().createOrReplace(kiboshDeployment);
			LOG.info("Created Deployment " + kiboshDeployment.getStatus());
		} else {
			LOG.severe("Loaded resource is not a Deployment! " + kiboshDeployment);
		}
		if (kiboshBazaarService instanceof Service) {
			kiboshBazaarService = client.services().createOrReplace(kiboshBazaarService);
			LOG.info("Created Service " + kiboshBazaarService.getStatus());
		} else {
			LOG.severe("Loaded resource is not a Service! " + kiboshBazaarService);
		}
		if (kiboshService instanceof Service) {
			kiboshService = client.services().createOrReplace(kiboshService);
			LOG.info("Created Service " + kiboshService.getStatus());
		} else {
			LOG.severe("Loaded resource is not a Service! " + kiboshService);
		}
		if (kiboshRBAC instanceof ServiceAccount) {
			kiboshRBAC = client.serviceAccounts().createOrReplace(kiboshRBAC);
			LOG.info("Created Service " + kiboshRBAC.getKind());
		} else {
			LOG.severe("Loaded resource is not a Service! " + kiboshRBAC);
		}

		client.close();
	}

	@Override
	public void run() {
		state = OperationState.IN_PROGRESS;
		operationStateMessage = "Preparing deployment";
		String serviceInstanceId=serviceInstanceRequest.getServiceInstanceId();
		//serviceInstanceId="c98498ac-d1af-4cbb-8286-42c4b3958fe6";
		
		JSONObject jsonClusterInfo;

		// SET HEADERS for PKS API
		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + pksRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		pksPlanName = getPlan(serviceInstanceRequest.getPlanId(), serviceInstanceRequest.getServiceDefinition())
				.getName();
		JSONObject payload = new JSONObject().put("name", serviceInstanceId)
				.put("plan_name", pksPlanName).put("parameters", new JSONObject()
						.put("kubernetes_master_host", TCP_FQDN).put("kubernetes_master_port", externalPort));

		
		HttpEntity<String> requestObject = new HttpEntity<String>(payload.toString(), headers);

		// FIRE REQUEST TO CREATE CLUSTER AGAINST PKS API
		// ONLY NECESSARY ON CREATE STEP
		if (action.equals("CREATE")) {
			pksRestTemplate.postForObject("https://" + PKS_FQDN + ":9021/v1/clusters", requestObject, String.class);
		}
		

		// CHECK CLUSTER CREATION PROCESS UNTIL ITS DONE
		JSONArray master_ips = new JSONArray();
		master_ips.put("empty");

		while (!InetAddressValidator.getInstance().isValid(master_ips.get(0).toString())
				&& (state != OperationState.FAILED || state != OperationState.SUCCEEDED)) {
			jsonClusterInfo = new JSONObject(pksRestTemplate.getForObject(
					"https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId,
					String.class));
			try {
				master_ips = (JSONArray) jsonClusterInfo.get("kubernetes_master_ips");
				this.state = jsonClusterInfo.get("last_action_state").equals("failed") ? OperationState.FAILED
						: OperationState.IN_PROGRESS;
				this.operationStateMessage = jsonClusterInfo.getString("last_action_description");

			} catch (HttpClientErrorException e) {
				switch (e.getStatusCode().toString()) {
				case "404":
					LOG.info("PKS Cluster: " + serviceInstanceId + " could not be found");
				case "401":
					LOG.severe("Broker could not authenticate with PKS API");
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

		LOG.info("Succesfully deployed PKS Cluster with name: " + serviceInstanceId);
		LOG.info("Applying Addons to : " + serviceInstanceId);

		// GET CLUSTER INFO
		jsonClusterInfo = new JSONObject(pksRestTemplate.getForObject(
				"https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId,
				String.class));

		// GET CLUSTER CONTEXT
		JSONObject jsonClusterContext = new JSONObject(pksRestTemplate.postForObject(
				"https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId + "/binds",
				requestObject, String.class));

		jsonClusterContext.put("master_ip",master_ips.getString(0));
		// CREATE ROUTE REG DEPLOYMENT FOR MASTER
		try {
			operationStateMessage = "Cluster created, creating route emitter pod";
			createRouteRegDeployment(jsonClusterContext, 8443,
					jsonClusterInfo.getJSONArray("kubernetes_master_ips").toList(),
					jsonClusterInfo.getJSONObject("parameters").getInt("kubernetes_master_port"), "master");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

		// First Check if PKS Cluster already has a Route Emitter Pod running
		while (!checkRouteRegDeployment(jsonClusterContext, "master")) {
			
			
			try {
				TimeUnit.SECONDS.sleep(10);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		operationStateMessage = "RouteRegistration Container deployed for Master";

	}

	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public OperationState getState() {
		return state;
	}

	public String getOperationStateMessage() {
		return operationStateMessage;
	}

	private Plan getPlan(String planId, ServiceDefinition serviceDef) {
		Iterator<Plan> it = serviceDef.getPlans().iterator();
		while (it.hasNext()) {
			Plan plan = it.next();
			if (planId.equals(plan.getId())) {
				return plan;
			}
		}
		return null;
	}
//	if (jsonClusterInfo.get("last_action_state").equals("failed"))
//	return GetLastServiceOperationResponse.builder().operationState(OperationState.FAILED)
//			.description(jsonClusterInfo.getString("last_action_description")).build();
//JSONArray master_ips = (JSONArray) jsonClusterInfo.get("kubernetes_master_ips");
//// BOSH is done deploying cluster
//if (InetAddressValidator.getInstance().isValid(master_ips.get(0).toString())
//		&& jsonClusterInfo.getString("last_action_state").equals("succeeded")) {
//	log.info("Succesfully deployed PKS Cluster with name: " + serviceInstanceId);
//	
//	
//	// SET HEADERS for PKS API
//	HttpHeaders headers = new HttpHeaders();
//	headers.add("Host", PKS_FQDN + ":9021");
//	headers.add("Accept", "application/json");
//	headers.add("Content-Type", "application/json");
//	headers.add("Authorization", "Bearer " + pksRestTemplate.getAccessToken());
//	headers.add("Accept-Encoding", "gzip");
//
//	HttpEntity<String> requestObject = new HttpEntity<String>("", headers);
//	JSONObject jsonClusterContext = new JSONObject(pksRestTemplate.postForObject(
//			"https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId + "/binds", requestObject,
//			String.class));
//	jsonClusterContext.put("master_ip", master_ips.get(0));
//	// First Check if PKS Cluster already has a Route Emitter Pod running
//	HashMap<String, Boolean> deploymentStatus = routeRegistrarDeployment
//			.checkRouteRegMaster(jsonClusterContext, "route-registrar-master");
//
//	if (deploymentStatus.get("operational")) {
//		operationStateMessage = "Cluster created, route emitter pod up and running";
//		state = OperationState.SUCCEEDED;
//	} else {
//		state = OperationState.IN_PROGRESS;
//		if (deploymentStatus.get("exists")) {
//			operationStateMessage = "Cluster created, waiting for route emitter to be up and running";
//		} else {
//			try {
//				operationStateMessage = "Cluster created, creating route emitter pod";
//				routeRegistrarDeployment.createRouteRegDeployment(jsonClusterContext, 8443,
//						jsonClusterInfo.getJSONArray("kubernetes_master_ips").toList(),
//						jsonClusterInfo.getJSONObject("parameters").getInt("kubernetes_master_port"),
//						"master");
//			} catch (FileNotFoundException e) {
//				e.printStackTrace();
//			}
//		}
//	}
//	// BOSH deployment of Cluster is still in progress
//} else {
//	state = OperationState.IN_PROGRESS;
//	operationStateMessage = "PKS Cluster " + jsonClusterInfo.getString("last_action_description");
//}
}