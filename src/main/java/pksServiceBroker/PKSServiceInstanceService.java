package pksServiceBroker;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.servicebroker.model.catalog.Catalog;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Service
public class PKSServiceInstanceService implements ServiceInstanceService {
	private static Logger LOG = LogManager.getLogger(PKSServiceInstanceService.class);

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

	@Autowired
	Catalog catalog;

	private static HashMap<String, PKSServiceInstanceAddonDeploymentsRunnable> addonDeploymentRunnables = new HashMap<>(
			0);
	private static HashMap<String, PKSServiceInstanceLastOperationInfo> serviceInstanceOperationInfoMap = new HashMap<>(
			0);

	public CreateServiceInstanceResponse createServiceInstance(CreateServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();

		String planName = getPlan(request.getPlanId(), request.getServiceDefinition()).getName();
		JSONObject custom_params = new JSONObject(request.getParameters());
		if (!addonDeploymentRunnables.containsKey(serviceInstanceId)
				|| !addonDeploymentRunnables.get(serviceInstanceId).getState().equals(OperationState.IN_PROGRESS)) {
			addonDeploymentRunnables.put(serviceInstanceId,
					(PKSServiceInstanceAddonDeploymentsRunnable) appContext.getBean("addonDeploymentRunnable",
							Config.BrokerAction.CREATE, serviceInstanceId, planName, RoutingLayer.HTTP, custom_params));
			Thread thread = new Thread(addonDeploymentRunnables.get(serviceInstanceId));
			thread.start();
			thread.setName(serviceInstanceId);
			LOG.info(request.getOriginatingIdentity() + " requested creation of "
					+ request.getServiceDefinition().getName() + " with ID " + serviceInstanceId + " for "
					+ request.getContext());
		}

		return CreateServiceInstanceResponse.builder().async(true).build();
	}

	public UpdateServiceInstanceResponse updateServiceInstance(UpdateServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		String planName = "";
		JSONObject custom_params = new JSONObject(request.getParameters());
		if (!addonDeploymentRunnables.containsKey(serviceInstanceId)
				|| !addonDeploymentRunnables.get(serviceInstanceId).getState().equals(OperationState.IN_PROGRESS)) {
			addonDeploymentRunnables.put(serviceInstanceId,
					(PKSServiceInstanceAddonDeploymentsRunnable) appContext.getBean("addonDeploymentRunnable",
							BrokerAction.UPDATE, serviceInstanceId, planName, RoutingLayer.HTTP, custom_params));

			Thread thread = new Thread(addonDeploymentRunnables.get(serviceInstanceId));
			thread.setName(serviceInstanceId);
			thread.start();
			LOG.info(request.getOriginatingIdentity() + " requested update of "
					+ request.getServiceDefinition().getName() + " with ID " + serviceInstanceId + " for "
					+ request.getContext());
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
		return DeleteServiceInstanceResponse.builder().async(false).build();
	}

	public GetServiceInstanceResponse getServiceInstance(GetServiceInstanceRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		String planName = "";
		if (!addonDeploymentRunnables.containsKey(serviceInstanceId)) {
			addonDeploymentRunnables.put(serviceInstanceId,
					(PKSServiceInstanceAddonDeploymentsRunnable) appContext.getBean("addonDeploymentRunnable",
							Config.BrokerAction.CREATE, serviceInstanceId, planName, RoutingLayer.HTTP,
							new JSONObject()));
		}
		Map<String, String> clusterConfigData = addonDeploymentRunnables.get(serviceInstanceId).getClusterConfigMap()
				.getData();

		JSONObject dashboards = new JSONObject();
		if (clusterConfigData.containsKey("kubernetes.fqdn") && clusterConfigData.containsKey("kubernetes.port")
				&& clusterConfigData.containsKey("kubernetes.protocol")) {
			dashboards.put("kube_api", clusterConfigData.get("kubernetes.protocol")
					+ clusterConfigData.get("kubernetes.fqdn") + ":" + clusterConfigData.get("kubernetes.port"));
		}
		if (clusterConfigData.containsKey("bazaar.fqdn") && clusterConfigData.containsKey("bazaar.port")
				&& clusterConfigData.containsKey("bazaar.protocol")) {
			dashboards.put("kibosh_bazaar_endpoint", clusterConfigData.get("bazaar.protocol")
					+ clusterConfigData.get("bazaar.fqdn") + ":" + clusterConfigData.get("bazaar.port"));
		}
		if (clusterConfigData.containsKey("kibosh.fqdn") && clusterConfigData.containsKey("kibosh.port")
				&& clusterConfigData.containsKey("kibosh.protocol")) {
			dashboards.put("kibosh_service_broker", clusterConfigData.get("kibosh.protocol")
					+ clusterConfigData.get("kibosh.fqdn") + ":" + clusterConfigData.get("kibosh.port"));
		}
		addonDeploymentRunnables.remove(serviceInstanceId);
		return GetServiceInstanceResponse.builder().dashboardUrl(dashboards.toString()).build();
	}

	public GetLastServiceOperationResponse getLastOperation(GetLastServiceOperationRequest request) {
		String serviceInstanceId = request.getServiceInstanceId();
		if (!serviceInstanceOperationInfoMap.containsKey(serviceInstanceId))
			serviceInstanceOperationInfoMap.put(serviceInstanceId,
					new PKSServiceInstanceLastOperationInfo(request.getServiceInstanceId(), pksRestTemplate, sbConfig));

		PKSServiceInstanceLastOperationInfo lastOperationInfo = serviceInstanceOperationInfoMap.get(serviceInstanceId);
		OperationState state = lastOperationInfo.updateStatus().getOperationState();
		String operationStateMessage = lastOperationInfo.getOperationStateMessage();
		BrokerAction lastPKSAction = lastOperationInfo.getOperationAction();
		if (state.equals(OperationState.SUCCEEDED) || state.equals(OperationState.FAILED)) {
			LOG.info("Completed " + lastPKSAction + " of PKS Cluster: " + serviceInstanceId + " with " + state);
			addonDeploymentRunnables.remove(serviceInstanceId);
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