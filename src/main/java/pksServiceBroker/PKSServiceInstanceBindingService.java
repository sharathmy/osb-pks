package pksServiceBroker;

import java.util.logging.Logger;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceAppBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.DeleteServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.GetServiceInstanceAppBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.GetServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.GetServiceInstanceBindingResponse;
import org.springframework.cloud.servicebroker.service.ServiceInstanceBindingService;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.stereotype.Service;

import pksServiceBroker.Config.RoutingLayer;

@Service
public class PKSServiceInstanceBindingService implements ServiceInstanceBindingService {
	@Value("${pks.fqdn}")
	private String PKS_FQDN;
	@Value("${pcf.tcp}")
	private String TCP_FQDN;

	private static Logger LOG = Logger.getLogger(PKSServiceInstanceBindingService.class.getName());

	@Autowired
	@Qualifier("pks")
	OAuth2RestTemplate pksRestTemplate;

	@Autowired
	@Qualifier("route")
	OAuth2RestTemplate routeRestTemplate;

	@Autowired
	ApplicationContext appContext;

	@Override
	public CreateServiceInstanceBindingResponse createServiceInstanceBinding(
			CreateServiceInstanceBindingRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + pksRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		HttpEntity<String> requestObject = new HttpEntity<String>("", headers);
		LOG.info(request.getOriginatingIdentity() + " requested creation of Credentials PKS Cluster "
				+ serviceInstanceId + " for " + request.getContext());
		PKSServiceInstanceAddonDeploymentsRunnable runner = (PKSServiceInstanceAddonDeploymentsRunnable) appContext
				.getBean("addonDeploymentRunnable", Config.BrokerAction.GET, serviceInstanceId, "", RoutingLayer.HTTP);
		JSONObject clusterCredentials = new JSONObject(runner.getClusterConfigMap().getData());
		JSONObject response = new JSONObject(pksRestTemplate.postForObject(
				"https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId + "/binds", requestObject,
				String.class));
		response.put("pks-config-map", clusterCredentials);
		return CreateServiceInstanceAppBindingResponse.builder().credentials("k8s_context", response.toMap())
				.bindingExisted(false).build();
	}

	@Override
	public GetServiceInstanceBindingResponse getServiceInstanceBinding(GetServiceInstanceBindingRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Content-Type", "application/json");
		headers.add("Authorization", "Bearer " + pksRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		HttpEntity<String> requestObject = new HttpEntity<String>("", headers);
		LOG.info(request.getOriginatingIdentity() + " requested retrieval of Credentials PKS Cluster "
				+ serviceInstanceId);
		PKSServiceInstanceAddonDeploymentsRunnable runner = (PKSServiceInstanceAddonDeploymentsRunnable) appContext
				.getBean("addonDeploymentRunnable", Config.BrokerAction.GET, serviceInstanceId, "", RoutingLayer.HTTP);
		JSONObject clusterCredentials = new JSONObject(runner.getClusterConfigMap().getData());
		JSONObject response = new JSONObject(pksRestTemplate.postForObject(
				"https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId + "/binds", requestObject,
				String.class));
		response.put("pks-config-map", clusterCredentials);

		return GetServiceInstanceAppBindingResponse.builder()
				.credentials("k8s_context", pksRestTemplate.postForObject(
						"https://" + PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId, requestObject, String.class))
				.build();
	}

	@Override
	public void deleteServiceInstanceBinding(DeleteServiceInstanceBindingRequest request) {
		// TODO Auto-generated method stub
	}
}
