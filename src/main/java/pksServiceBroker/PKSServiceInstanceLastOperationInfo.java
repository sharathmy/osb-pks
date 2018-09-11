package pksServiceBroker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.cloud.servicebroker.model.instance.OperationState;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import pksServiceBroker.Config.BrokerAction;

public class PKSServiceInstanceLastOperationInfo {

	private static Logger LOG = LogManager.getLogger(PKSServiceInstanceLastOperationInfo.class);

	Config sbConfig;
	
	private JSONObject jsonClusterInfo;
	private KubernetesClient client = new DefaultKubernetesClient();
	private String serviceInstanceId;
	private boolean boshDone = false;
	private JSONArray master_ips;
	private OperationState state;
	private String operationStateMessage;
	private HttpEntity<String> pksRequestObject;
	PKSServiceBrokerKubernetesClientUtil clientUtil = new PKSServiceBrokerKubernetesClientUtil();
	

	OAuth2RestTemplate pksRestTemplate;

	private BrokerAction action;

	public PKSServiceInstanceLastOperationInfo(String serviceInstanceId, OAuth2RestTemplate pksRestTemplate,
			Config sbConfig) {
		this.serviceInstanceId = serviceInstanceId;
		this.pksRestTemplate = pksRestTemplate;
		this.sbConfig = sbConfig;
		this.state = OperationState.IN_PROGRESS;
		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", sbConfig.PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + pksRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		this.pksRequestObject = new HttpEntity<String>("", headers);
		this.clientUtil = new PKSServiceBrokerKubernetesClientUtil();
	}

	public PKSServiceInstanceLastOperationInfo updateStatus() {
		if (master_ips==null) {
			master_ips = new JSONArray();
			master_ips.put("empty");
		}
		if (!boshDone) {
			jsonClusterInfo = new JSONObject(pksRestTemplate.getForObject(
					"https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + this.serviceInstanceId, String.class));
			if (jsonClusterInfo.get("last_action_state").equals("succeeded")) {
				this.master_ips = (JSONArray) jsonClusterInfo.get("kubernetes_master_ips");
				System.err.println("setting master ips to "+master_ips.toString());
				this.boshDone = true;
				this.operationStateMessage = "Bosh finished creating Kubernetes VMs";
				return this;
			} else
				try {
					this.state = jsonClusterInfo.get("last_action_state").equals("failed") ? OperationState.FAILED
							: OperationState.IN_PROGRESS;
					this.operationStateMessage = jsonClusterInfo.getString("last_action_description");
				} catch (HttpClientErrorException e) {
					switch (e.getStatusCode().toString()) {
					case "404":
						LOG.trace("PKS Cluster: " + this.serviceInstanceId
								+ " Error 404 requesting cluster status from PKS API");
						break;
					case "401":
						LOG.trace("PKS Cluster: " + this.serviceInstanceId
								+ " Error 401 requesting cluster status from PKS API");
						break;
					default:
						break;
					}
					e.printStackTrace();
				}
		} else
			try {
				JSONObject jsonClusterContext = new JSONObject(pksRestTemplate.postForObject(
						"https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + this.serviceInstanceId + "/binds",
						this.pksRequestObject, String.class));
				jsonClusterContext.put("master_ip", this.master_ips.getString(0));
				System.err.println(jsonClusterContext);
				clientUtil.setUseExternalRoute(false);
				client = clientUtil.changeClient(jsonClusterContext, "kube-system");
				ConfigMap lastOpConfigMap = client.configMaps().load(PKSServiceInstanceLastOperationInfo.class
						.getClassLoader().getResourceAsStream(Config.lastOpConfigMapFilename)).get();
				lastOpConfigMap = client.configMaps().withName(lastOpConfigMap.getMetadata().getName()).fromServer().get();
				this.state = OperationState.valueOf(lastOpConfigMap.getData().get("state"));
				this.operationStateMessage = lastOpConfigMap.getData().get("message");
				this.action = BrokerAction.valueOf(lastOpConfigMap.getData().get("action"));
				System.err.println(lastOpConfigMap.toString());
				
			} catch (Exception e) {
				e.printStackTrace();
				client.close();
			}
		client.close();
		return this;
	}

	public String getOperationStateMessage() {
		return this.operationStateMessage;
	}

	public OperationState getOperationState() {
		return this.state;
	}

	public BrokerAction getOperationAction() {
		return this.action;
	}
}
