package pksServiceBroker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.servicebroker.model.instance.CreateServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.CreateServiceInstanceResponse;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceResponse;
import org.springframework.cloud.servicebroker.model.instance.GetLastServiceOperationRequest;
import org.springframework.cloud.servicebroker.model.instance.GetLastServiceOperationResponse;
import org.springframework.cloud.servicebroker.model.instance.GetServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.GetServiceInstanceResponse;
import org.springframework.cloud.servicebroker.model.instance.OperationState;
import org.springframework.cloud.servicebroker.model.instance.UpdateServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.UpdateServiceInstanceResponse;
import org.springframework.cloud.servicebroker.service.ServiceInstanceService;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.logging.Logger;

@Service
public class PKSServiceInstanceService implements ServiceInstanceService {
	private static Logger log = Logger.getLogger(PKSServiceInstanceService.class.getName());

	@Value("${pks.fqdn}")
	private String PKS_FQDN;
	@Value("${pcf.tcp}")
	private String TCP_FQDN;

	@Autowired
	@Qualifier("pks")
	OAuth2RestTemplate pksRestTemplate;

	@Autowired
	@Qualifier("route")
	OAuth2RestTemplate routeRestTemplate;

	@Autowired
	private ApplicationContext appContext;
	
	HashMap<String, PKSServiceInstanceAddonDeploymentsRunnable> addonDeploymentRunnables = new HashMap<>(0);

	public CreateServiceInstanceResponse createServiceInstance(CreateServiceInstanceRequest request) {

//		
//		log.info(request.getOriginatingIdentity() + " requested creation of "
//				+ getPlan(planId, request.getServiceDefinition()) + " with ID " + serviceInstanceId + " for "
//				+ request.getContext());
//		request.getParameters().get("kibosh");
//		// PARSE PARAMS
//		String planName = getPlan(planId, request.getServiceDefinition()).getName();
//
//		// CREATE REQUEST TO PKS API
//		HttpHeaders headers = new HttpHeaders();
//
//		headers.add("Host", PKS_FQDN + ":9021");
//		headers.add("Accept", "application/json");
//		headers.add("Content-Type", "application/json");
//		headers.add("Authorization", "Bearer " + pksRestTemplate.getAccessToken());
//		headers.add("Accept-Encoding", "gzip");
//		JSONObject payload = new JSONObject().put("name", serviceInstanceId).put("plan_name", planName).put(
//				"parameters",
//				new JSONObject().put("kubernetes_master_host", TCP_FQDN).put("kubernetes_master_port", externalPort));
//
//		HttpEntity<String> requestObject = new HttpEntity<String>(payload.toString(), headers);
//		pksRestTemplate.postForObject("https://" + PKS_FQDN + ":9021/v1/clusters", requestObject, String.class);
		
		if (!addonDeploymentRunnables.containsKey(request.getServiceInstanceId())) {
			addonDeploymentRunnables.put(request.getServiceInstanceId(),
					(PKSServiceInstanceAddonDeploymentsRunnable) appContext.getBean("addonDeploymentRunnable", "CREATE", request));
			Thread thread = new Thread((PKSServiceInstanceAddonDeploymentsRunnable) appContext.getBean("addonDeploymentRunnable", "CREATE", request));
			thread.start();
			log.info(request.getOriginatingIdentity() + " requested creation of "+ request.getServiceDefinition().getName() + " with ID "
					+ request.getServiceInstanceId() + " for " + request.getContext());
		}
		return CreateServiceInstanceResponse.builder()
				.dashboardUrl("https://" + TCP_FQDN + ":"
						+ addonDeploymentRunnables.get(request.getServiceInstanceId()).getExternalPort())
				.async(true).build();
	}

	public UpdateServiceInstanceResponse updateServiceInstance(UpdateServiceInstanceRequest request) {
//		String serviceInstanceId = request.getServiceInstanceId();
//		String planId = request.getPlanId();
//		String previousPlan = request.getPreviousValues().getPlanId();
//		Map<String, Object> parameters = request.getParameters();
		return UpdateServiceInstanceResponse.builder().async(true).build();
	}

	public DeleteServiceInstanceResponse deleteServiceInstance(DeleteServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
//		String planId = request.getPlanId();
		
		log.info(request.getOriginatingIdentity() + " requested deletetion of PKS Cluster :" + serviceInstanceId);

		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Authorization", "Bearer " + pksRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		HttpEntity<String> requestObject = new HttpEntity<String>("", headers);
		pksRestTemplate.exchange("https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId, HttpMethod.DELETE,
				requestObject, String.class);
		return DeleteServiceInstanceResponse.builder().async(true).build();
	}

	public GetServiceInstanceResponse getServiceInstance(GetServiceInstanceRequest request) {
		// String serviceInstanceId = request.getServiceInstanceId();
		//
		// retrieve the details of the specified service instance
		//
		String dashboardUrl = new String(/* retrieve dashboard URL */);

		return GetServiceInstanceResponse.builder().dashboardUrl(dashboardUrl).build();
	}

	public GetLastServiceOperationResponse getLastOperation(GetLastServiceOperationRequest request) {
		OperationState state = null;
		String operationStateMessage = "Applying Changes";

		System.err.println("calling last operation for "+request.getServiceInstanceId());
		String lastPKSAction = addonDeploymentRunnables.get(request.getServiceInstanceId()).getAction();
		switch (lastPKSAction) {
		case "CREATE":
			state = addonDeploymentRunnables.get(request.getServiceInstanceId()).getState();
			operationStateMessage = addonDeploymentRunnables.get(request.getServiceInstanceId()).getOperationStateMessage();
			
			if (state.equals(OperationState.SUCCEEDED) || state.equals(OperationState.FAILED));
				addonDeploymentRunnables.remove(request.getServiceInstanceId());

			break;
		case "UPDATE":
			state = addonDeploymentRunnables.get(request.getServiceInstanceId()).getState();
			operationStateMessage = addonDeploymentRunnables.get(request.getServiceInstanceId()).getOperationStateMessage();
			break;
		case "DELETE":
			// currently will throw error once deletion is complete...
			state = addonDeploymentRunnables.get(request.getServiceInstanceId()).getState();
			operationStateMessage = addonDeploymentRunnables.get(request.getServiceInstanceId()).getOperationStateMessage();
			break;
		}
		state = state == null ? OperationState.FAILED : state;
		return GetLastServiceOperationResponse.builder().operationState(state).description(operationStateMessage)
				.build();
	}

}