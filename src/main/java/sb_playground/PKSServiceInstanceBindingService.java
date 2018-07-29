package sb_playground;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceAppBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.DeleteServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.GetServiceInstanceAppBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.GetServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.GetServiceInstanceBindingResponse;
import org.springframework.cloud.servicebroker.service.ServiceInstanceBindingService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2RestOperations;
import org.springframework.stereotype.Service;

@Service
public class PKSServiceInstanceBindingService implements ServiceInstanceBindingService {
	@Value("${pks.fqdn}")
	private String PKS_FQDN;
	@Value("${pks.apps_tcp_fqdn}")
	private String TCP_FQDN;
	
	@Autowired
	OAuth2RestOperations restTemplate;

	@Override
	public CreateServiceInstanceBindingResponse createServiceInstanceBinding(
			CreateServiceInstanceBindingRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();

		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + restTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		HttpEntity<String> requestObject = new HttpEntity<String>("", headers);
		JSONObject response = new JSONObject(
				restTemplate.postForObject("https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId + "/binds",
						requestObject, String.class));
		return CreateServiceInstanceAppBindingResponse.builder().credentials("k8s_context", response.toString())
				.bindingExisted(false).build();
	}

	@Override
	public GetServiceInstanceBindingResponse getServiceInstanceBinding(GetServiceInstanceBindingRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + restTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		HttpEntity<String> requestObject = new HttpEntity<String>("", headers);
		return GetServiceInstanceAppBindingResponse
				.builder()
				.credentials("k8s_context", restTemplate.postForObject("https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId + "/binds",
						requestObject, String.class))
				.build();
	}

	@Override
	public void deleteServiceInstanceBinding(DeleteServiceInstanceBindingRequest request) {
		// TODO Auto-generated method stub

	}

}
