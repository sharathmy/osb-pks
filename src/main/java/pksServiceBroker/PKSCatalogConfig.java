package pksServiceBroker;

import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.servicebroker.model.catalog.Catalog;
import org.springframework.cloud.servicebroker.model.catalog.Plan;
import org.springframework.cloud.servicebroker.model.catalog.ServiceDefinition;
import org.springframework.cloud.servicebroker.service.CatalogService;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.stereotype.Service;


@Service
public class PKSCatalogConfig implements CatalogService{
	@Autowired
	@Qualifier("pks")
	OAuth2RestTemplate pksRestTemplate;

	@Value("${pks.fqdn}")
	private String PKS_FQDN;
	
	@Value("${servicebroker.service.id}")
	private String id;
	@Value("${servicebroker.service.name}")
	private String serviceName;
	@Value("${servicebroker.service.description}")
	private String serviceDescription;
	@Value("${servicebroker.service.bindable}")
	private Boolean bindable; 
	
	@Override
	@Bean
	public Catalog getCatalog() {
		return Catalog.builder().serviceDefinitions(getServiceDefinition(id)).build();
	}
	@Override
	public ServiceDefinition getServiceDefinition(String serviceId) {
		ServiceDefinition serviceDefinition = ServiceDefinition.builder()
				.id(serviceId)
				.name(serviceName)
				.description(serviceDescription)
				.bindable(bindable)
				.plans(getPlans()).build();

		return serviceDefinition;
	}
	private ArrayList<Plan> getPlans(){
		String plansString = pksRestTemplate.getForObject("https://"+PKS_FQDN+":9021/v1/plans", String.class);
		ArrayList<Plan> plans= new ArrayList<Plan>();
		JSONArray response = new JSONArray(plansString);
		for (int i = 0; i < response.length(); i++) {
			JSONObject plan = response.getJSONObject(i);
			plans.add(
					Plan.builder()
						.id(plan.getString("id"))
						.name(plan.getString("name"))
						.description(plan.getString("description")+" "+ plan.getInt("worker_instances")+"  Worker Instances")
						.bindable(true)
						.build()
					);
		}
		return plans;
	}
}