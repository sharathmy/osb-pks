package pksServiceBroker;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.servicebroker.model.catalog.Plan;
import org.springframework.cloud.servicebroker.model.catalog.ServiceDefinition;
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

import pksServiceBroker.Config.BrokerAction;
import pksServiceBroker.Config.RoutingLayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

@Service
public class PKSServiceInstanceService implements ServiceInstanceService {
	private static Logger LOG = Logger.getLogger(PKSServiceInstanceService.class.getName());

	@Autowired
	@Qualifier("pks")
	OAuth2RestTemplate pksRestTemplate;

	@Autowired
	@Qualifier("route")
	OAuth2RestTemplate routeRestTemplate;

	@Autowired
	Config sbConfig;

	@Autowired
	private ApplicationContext appContext;

	private static HashMap<String, PKSServiceInstanceAddonDeploymentsRunnable> addonDeploymentRunnables = new HashMap<>(
			0);

	public CreateServiceInstanceResponse createServiceInstance(CreateServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();

		String planName = getPlan(request.getPlanId(), request.getServiceDefinition()).getName();

		if (!addonDeploymentRunnables.containsKey(serviceInstanceId)) {
			addonDeploymentRunnables.put(serviceInstanceId,
					(PKSServiceInstanceAddonDeploymentsRunnable) appContext.getBean("addonDeploymentRunnable",
							Config.BrokerAction.CREATE, serviceInstanceId, planName, RoutingLayer.HTTP));
			Thread thread = new Thread(addonDeploymentRunnables.get(serviceInstanceId));
			thread.start();
			LOG.info(request.getOriginatingIdentity() + " requested creation of "
					+ request.getServiceDefinition().getName() + " with ID " + serviceInstanceId + " for "
					+ request.getContext());
		}
		Map<String, String> clusterConfigData = addonDeploymentRunnables.get(serviceInstanceId).getClusterConfigMap()
				.getData();
		JSONObject dashboards = new JSONObject();
		String KUBE_API_HTTP_ADDR = clusterConfigData.get("kubernetes.protocoll")
				+ clusterConfigData.get("kubernetes.fqdn") + ":" + clusterConfigData.get("kubernetes.port");
		String BAZAAR_API_HTTP_ADDR = clusterConfigData.get("bazaar.protocoll") + clusterConfigData.get("bazaar.fqdn")
				+ ":" + clusterConfigData.get("bazaar.port");

		String KIBOSH_API_HTTP_ADDR = clusterConfigData.get("kibosh.protocoll") + clusterConfigData.get("kibosh.fqdn")
				+ ":" + clusterConfigData.get("kibosh.port");
		dashboards.put("kube_api", KUBE_API_HTTP_ADDR);
		dashboards.put("kibosh_service_broker", KIBOSH_API_HTTP_ADDR);
		dashboards.put("kibosh_bazaar_endpoint", BAZAAR_API_HTTP_ADDR);

		return CreateServiceInstanceResponse.builder().dashboardUrl(dashboards.toString()).async(true).build();
	}

	public UpdateServiceInstanceResponse updateServiceInstance(UpdateServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		// serviceInstanceId="c98498ac-d1af-4cbb-8286-42c4b3958fe6";
		if (!addonDeploymentRunnables.containsKey(serviceInstanceId)) {
			addonDeploymentRunnables.put(serviceInstanceId, (PKSServiceInstanceAddonDeploymentsRunnable) appContext
					.getBean("addonDeploymentRunnable", "UPDATE", request));
			Thread thread = new Thread(addonDeploymentRunnables.get(serviceInstanceId));
			thread.start();
			LOG.info(request.getOriginatingIdentity() + " requested update of "
					+ request.getServiceDefinition().getName() + " with ID " + serviceInstanceId + " for "
					+ request.getContext());
		} else {
			return UpdateServiceInstanceResponse.builder().async(false).build();
		}

		return UpdateServiceInstanceResponse.builder().async(true).build();
	}

	public DeleteServiceInstanceResponse deleteServiceInstance(DeleteServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();

		LOG.info(request.getOriginatingIdentity() + " requested deletetion of PKS Cluster :" + serviceInstanceId);

		HttpHeaders headers = new HttpHeaders();
		headers.add("Host", sbConfig.PKS_FQDN + ":9021");
		headers.add("Accept", "application/json");
		headers.add("Authorization", "Bearer " + pksRestTemplate.getAccessToken());
		headers.add("Accept-Encoding", "gzip");
		HttpEntity<String> requestObject = new HttpEntity<String>("", headers);
		pksRestTemplate.exchange("https://" + sbConfig.PKS_FQDN + ":9021/v1/clusters/" + serviceInstanceId,
				HttpMethod.DELETE, requestObject, String.class);
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
		String serviceInstanceId = request.getServiceInstanceId();
		OperationState state = addonDeploymentRunnables.get(serviceInstanceId).getState();
		String operationStateMessage = addonDeploymentRunnables.get(serviceInstanceId).getOperationStateMessage();
		BrokerAction lastPKSAction = addonDeploymentRunnables.get(serviceInstanceId).getAction();
		switch (lastPKSAction) {
		case CREATE:
			if (state.equals(OperationState.SUCCEEDED) || state.equals(OperationState.FAILED))
				addonDeploymentRunnables.remove(serviceInstanceId);
			break;
		case UPDATE:
			break;
		case GET:
			break;
		case DELETE:
			break;
		}
		return GetLastServiceOperationResponse.builder().operationState(state).description(operationStateMessage)
				.build();
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