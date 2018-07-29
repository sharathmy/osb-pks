package sb_playground;

import org.apache.commons.validator.routines.InetAddressValidator;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingResponse;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2RestOperations;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PKSServiceInstanceService implements ServiceInstanceService {
	@Value("${pks.fqdn}")
	private String PKS_FQDN;
	@Value("${pks.apps_tcp_fqdn}")
	private String TCP_FQDN;
	@Autowired
	OAuth2RestOperations restTemplate;

	public CreateServiceInstanceResponse createServiceInstance(CreateServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		String planId = request.getPlanId();
		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + restTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		JSONObject payload= new JSONObject()
				.put("name", serviceInstanceId)
				.put("plan_name", planId)
				.put("parameters", new JSONObject()
						.put("kubernetes_master_host", "master." + serviceInstanceId + ".local")
						);
		HttpEntity<String> requestObject = new HttpEntity<String>(payload.toString(), headers);
		restTemplate.postForObject("https://" + PKS_FQDN + ":9021/v1/clusters", requestObject, String.class);
		String dashboardUrl = serviceInstanceId + "." + TCP_FQDN;
		return CreateServiceInstanceResponse.builder().dashboardUrl(dashboardUrl).async(true).build();
	}
	public UpdateServiceInstanceResponse updateServiceInstance(UpdateServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		String planId = request.getPlanId();
		String previousPlan = request.getPreviousValues().getPlanId();
		Map<String, Object> parameters = request.getParameters();
		return UpdateServiceInstanceResponse.builder().async(true).build();
	}

	public DeleteServiceInstanceResponse deleteServiceInstance(DeleteServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		String planId = request.getPlanId();

		//
		// perform the steps necessary to initiate the asynchronous
		// deletion of all provisioned resources
		//

		return DeleteServiceInstanceResponse.builder().async(true).build();
	}

	public GetServiceInstanceResponse getServiceInstance(GetServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		//
		// retrieve the details of the specified service instance
		//
		String dashboardUrl = new String(/* retrieve dashboard URL */);

		return GetServiceInstanceResponse.builder().dashboardUrl(dashboardUrl).build();
	}

	public GetLastServiceOperationResponse getLastOperation(GetLastServiceOperationRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();

		//
		// determine the status of the operation in progress
		//
		OperationState state;
		JSONObject jsonClusterInfo = new JSONObject(restTemplate
				.getForObject("https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId, String.class));
		JSONArray master_ips = (JSONArray) jsonClusterInfo.get("kubernetes_master_ips");
		if (InetAddressValidator.getInstance().isValid(master_ips.get(0).toString()))
			state = OperationState.SUCCEEDED;
		else {
			state = OperationState.IN_PROGRESS;
		}

		return GetLastServiceOperationResponse.builder().operationState(state).description("Deployment in Progress").build();
	}
}