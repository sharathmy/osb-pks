package pksServiceBroker;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
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
	private static String kiboshRBACAccountFilename = "config/kibosh-rbac-service-account.yaml";
	private static String kiboshRBACBindingFilename = "config/kibosh-rbac-cr-binding.yaml";
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
	private String action;
	private JSONObject jsonClusterInfo;
	private HttpHeaders pksHeaders;
	private HttpHeaders routeHeaders;
	private HttpEntity<String> routeRequestObject;
	private HttpEntity<String> pksRequestObject;
	String serviceInstanceId;

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
		// INITIALIZATION
		state = OperationState.IN_PROGRESS;
		operationStateMessage = "Preparing deployment";
		runner.setAction(action);
		runner.setServiceInstanceId(request.getServiceInstanceId());

		// DEBUG OVERRIDE
		// runner.setServiceInstanceId("e162add3-4cdc-4aa2-bca0-1cba77005026");

		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + pksRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		runner.setPksHeaders(headers);
		headers = new HttpHeaders();
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + routeRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		runner.setRouteHeaders(headers);

		switch (action) {
		case "UPDATE":
			runner.setJsonClusterInfo(new JSONObject(pksRestTemplate.getForObject(
					"https://" + PKS_FQDN + ":9021/v1/clusters/" + runner.getServiceInstanceId(), String.class)));
			runner.setExternalPort(
					runner.getJsonClusterInfo().getJSONObject("parameters").getInt("kubernetes_master_port"));
			break;
		case "CREATE":
			runner.setExternalPort(getFreePortForCFTcpRouter());
			break;
		default:
			break;
		}
		JSONObject payload = new JSONObject().put("name", runner.getServiceInstanceId())
				.put("plan_name", getPlan(request.getPlanId(), request.getServiceDefinition()).getName())
				.put("parameters", new JSONObject().put("kubernetes_master_host", TCP_FQDN)
						.put("kubernetes_master_port", runner.getExternalPort()));

		runner.setPksRequestObject(new HttpEntity<String>(payload.toString(), runner.getPksHeaders()));
		runner.setRouteRequestObject(new HttpEntity<String>("", runner.getRouteHeaders()));
		return runner;
	}

	private String getServiceInstanceId() {
		// TODO Auto-generated method stub
		return this.serviceInstanceId;
	}

	private void setExternalPort(int externalPort) {
		this.externalPort = externalPort;
	}

	public PKSServiceInstanceAddonDeploymentsRunnable() {
		this.state = OperationState.IN_PROGRESS;
		this.operationStateMessage = "Started Deployment";
	}

	// GETTER & SETTER Block
	public int getExternalPort() {
		return externalPort;
	}

	private KubernetesClient getClient(JSONObject kubeConfig, boolean internalRoute, Boolean namespaced) {
		String masterURL = "";
		if (internalRoute) {
			masterURL = "https://" + kubeConfig.getString("master_ip") + ":" + kubePort + "/";
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
					.withNamespace(namespace).withTrustCerts(true).build();
		else
			config = new ConfigBuilder().withMasterUrl(masterURL)
					.withOauthToken(
							kubeConfig.getJSONArray("users").getJSONObject(0).getJSONObject("user").getString("token"))
					.withCaCertData(kubeConfig.getJSONArray("clusters").getJSONObject(0).getJSONObject("cluster")
							.getString("certificate-authority-data"))
					.withTrustCerts(true).build();

		KubernetesClient client = new DefaultKubernetesClient(config);

		return client;
	}

	protected int getFreePortForCFTcpRouter() {
		int portMin = Integer.parseInt(portRange.split("-")[0]);
		int portMax = Integer.parseInt(portRange.split("-")[1]);
		int portMaster = new Random().nextInt(portMax - portMin + 1) + portMin;
		Set<Integer> ports = new LinkedHashSet<>();
		JSONArray routes = new JSONArray(routeRestTemplate.exchange("https://" + pcfApi + "/routing/v1/tcp_routes",
				HttpMethod.GET, routeRequestObject, String.class).getBody().toString());
		routes.forEach((route) -> {
			ports.add(new JSONObject(route.toString()).getInt("port"));
		});
		while (ports.contains(portMaster))
			portMaster = new Random().nextInt(portMax - portMin + 1) + portMin;
		return portMaster;
	}

	protected Boolean checkRouteRegDeployment(JSONObject kubeConfig, String componentName) {
		Boolean cont = true;
		KubernetesClient client = getClient(kubeConfig, true, true);
		String deploymentName = ROUTE_DEPLOYMENT_PREFIX + componentName;
		Deployment deployment = client.apps().deployments().withName(deploymentName).fromServer().get();
		if (deployment == null) {
			state = OperationState.FAILED;
			cont = false;
		} else {
			client.close();
			client = getClient(kubeConfig, false, false);
			if (client != null) {
				try {
					deployment = client.apps().deployments().withName(deploymentName).fromServer().get();
					cont = false;
					operationStateMessage = "Master Routes active";
				} catch (KubernetesClientException e) {
					// our try used external url, so we use Exception to figure out whether route is
					// ready
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
			int externalPort, String componentName) throws FileNotFoundException {
		String deploymentName = ROUTE_DEPLOYMENT_PREFIX + componentName;
		KubernetesClient client = getClient(kubeConfig, true, true);

		Deployment routeEmitDeployment = client.apps().deployments()
				.load(routeEmitDeploymentFilename).get();

		if (routeEmitDeployment instanceof Deployment) {
			routeEmitDeployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().iterator()
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
			routeEmitDeployment.getMetadata().setName(deploymentName);
			routeEmitDeployment.getSpec().getTemplate().getMetadata().getLabels().put("name", deploymentName);
			routeEmitDeployment.getSpec().getSelector().getMatchLabels().put("name", deploymentName);
			routeEmitDeployment.getSpec().getTemplate().getSpec().getContainers().get(0).setName(deploymentName);
			routeEmitDeployment = client.apps().deployments().createOrReplace(routeEmitDeployment);
			LOG.info("Created Deployment " + routeEmitDeployment.getStatus());

		} else {
			LOG.severe("Loaded resource is not a Deployment! " + routeEmitDeployment);
		}
		client.close();
	}

	protected void createKiboshDeployment(JSONObject jsonClusterContext) throws FileNotFoundException {
		KubernetesClient client;
		client = getClient(jsonClusterContext, false, true);
		Deployment kiboshDeployment = client.apps().deployments().load(kiboshDeploymentFilename).get();
		Service kiboshBazaarService = client.services().load(bazaarServiceFilename).get();
		Service kiboshService = client.services().load(kiboshServiceFilename).get();
		ServiceAccount kiboshRBACAccount = client.serviceAccounts().load(kiboshRBACAccountFilename).get();
		KubernetesClusterRoleBinding kiboshRBACBinding = client.rbac().kubernetesClusterRoleBindings()
				.load(kiboshRBACBindingFilename).get();
		if (kiboshRBACAccount instanceof ServiceAccount) {
			kiboshRBACAccount = client.serviceAccounts().createOrReplace(kiboshRBACAccount);
			LOG.info("Created Service " + kiboshRBACAccount.getKind());
		} else {
			LOG.severe("Loaded resource is not an Account! " + kiboshRBACAccount);
		}
		if (kiboshDeployment instanceof Deployment) {
			kiboshDeployment = client.apps().deployments().createOrReplace(kiboshDeployment);
			LOG.info("Created Deployment " + kiboshDeployment.getStatus());
		} else {
			LOG.severe("Loaded resource is not a Deployment! " + kiboshDeployment);
		}

		if (kiboshBazaarService instanceof Service) {
			kiboshBazaarService = client.services().createOrReplace(kiboshBazaarService);
			LOG.info("Created Bazaar Service " + kiboshBazaarService.getStatus());
		} else {
			LOG.severe("Loaded resource is not a Service! " + kiboshBazaarService);
		}
		if (kiboshService instanceof Service) {
			kiboshService = client.services().createOrReplace(kiboshService);
			LOG.info("Created KIBOSH Service " + kiboshService.getStatus());
		} else {
			LOG.severe("Loaded resource is not a Service! " + kiboshService);
		}
		if (kiboshRBACBinding instanceof KubernetesClusterRoleBinding) {
			client.rbac().kubernetesClusterRoleBindings().createOrReplace(kiboshRBACBinding);
			LOG.info("Created Service " + kiboshRBACBinding.getKind());
		} else {
			LOG.severe("Loaded resource is not a Role Binding! " + kiboshRBACBinding);
		}
		JSONArray nodeIPs = new JSONArray();

		client.nodes().list().getItems().forEach((Node node) -> {
			nodeIPs.put(node.getStatus().getAddresses().get(0).getAddress());
		});
		createRouteRegDeployment(jsonClusterContext, kiboshService.getSpec().getPorts().get(0).getNodePort(),
				nodeIPs.toList(), getFreePortForCFTcpRouter(), "kibosh");
		createRouteRegDeployment(jsonClusterContext, kiboshBazaarService.getSpec().getPorts().get(0).getNodePort(),
				nodeIPs.toList(), getFreePortForCFTcpRouter(), "kibosh-bazaar");
		client.close();

	}

	@Override
	public void run() {

		JSONArray master_ips = new JSONArray();
		master_ips.put("empty");

		// SWITCH TO HANDLE CHANGES IN WORKFLOWS
		switch (action) {
		case "CREATE":
			pksRestTemplate.postForObject("https://" + PKS_FQDN + ":9021/v1/clusters", pksRequestObject, String.class);
			break;
		case "UPDATE":
			break;
		default:
			break;
		}
		// CHECK CLUSTER CREATION PROCESS UNTIL ITS DONE
		while (!InetAddressValidator.getInstance().isValid(master_ips.get(0).toString())
				&& (state != OperationState.FAILED || state != OperationState.SUCCEEDED)) {
			System.err.println(serviceInstanceId);
			jsonClusterInfo = new JSONObject(pksRestTemplate
					.getForObject("https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId, String.class));
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
		jsonClusterInfo = new JSONObject(pksRestTemplate
				.getForObject("https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId, String.class));

		// GET CLUSTER CONTEXT
		JSONObject jsonClusterContext = new JSONObject(pksRestTemplate.postForObject(
				"https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId + "/binds", pksRequestObject,
				String.class));

		jsonClusterContext.put("master_ip", master_ips.getString(0));
		// CREATE ROUTE REG DEPLOYMENT FOR MASTER
		try {
			operationStateMessage = "Cluster created, creating route emitter pod";
			createRouteRegDeployment(jsonClusterContext, 8443,
					jsonClusterInfo.getJSONArray("kubernetes_master_ips").toList(),
					jsonClusterInfo.getJSONObject("parameters").getInt("kubernetes_master_port"), "master");
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

	public HttpHeaders getPksHeaders() {
		return pksHeaders;
	}

	public void setPksHeaders(HttpHeaders pksHeaders) {
		this.pksHeaders = pksHeaders;
	}

	public HttpHeaders getRouteHeaders() {
		return routeHeaders;
	}

	public void setRouteHeaders(HttpHeaders routeHeaders) {
		this.routeHeaders = routeHeaders;
	}

	public HttpEntity<String> getRouteRequestObject() {
		return routeRequestObject;
	}

	public void setRouteRequestObject(HttpEntity<String> routeRequestObject) {
		this.routeRequestObject = routeRequestObject;
	}

	public HttpEntity<String> getPksRequestObject() {
		return pksRequestObject;
	}

	public void setPksRequestObject(HttpEntity<String> pksRequestObject) {
		this.pksRequestObject = pksRequestObject;
	}

	public void setServiceInstanceId(String serviceInstanceId) {
		this.serviceInstanceId = serviceInstanceId;
	}

	public JSONObject getJsonClusterInfo() {
		return jsonClusterInfo;
	}

	public void setJsonClusterInfo(JSONObject jsonClusterInfo) {
		this.jsonClusterInfo = jsonClusterInfo;
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
}