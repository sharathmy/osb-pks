package pksServiceBroker;

import org.json.JSONObject;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;

public class PKSServiceBrokerKubernetesClientUtil {
	private boolean useExternalRoute = true;

	public void setUseExternalRoute(boolean useExternalRoute) {
		this.useExternalRoute = useExternalRoute;
	}

	public PKSServiceBrokerKubernetesClientUtil() {
		this.useExternalRoute = false;
	}

	private DefaultKubernetesClient client = new DefaultKubernetesClient();

	public DefaultKubernetesClient changeClient(JSONObject jsonClusterContext, String namespace) {
		String masterURL = "";
		if (useExternalRoute) {
			masterURL = jsonClusterContext.getJSONArray("clusters").getJSONObject(0).getJSONObject("cluster")
					.getString("server");
		} else {
			masterURL = "https://" + jsonClusterContext.getString("master_ip") + ":"
					+ pksServiceBroker.Config.KUBERNETES_MASTER_PORT + "/";
		}
		System.err.println(masterURL);
		Config config;
		if (namespace != null && namespace != "")
			config = new ConfigBuilder().withMasterUrl(masterURL)
					.withOauthToken(jsonClusterContext.getJSONArray("users").getJSONObject(0).getJSONObject("user")
							.getString("token"))
					.withCaCertData(jsonClusterContext.getJSONArray("clusters").getJSONObject(0)
							.getJSONObject("cluster").getString("certificate-authority-data"))
					.withNamespace(namespace).withTrustCerts(true).build();
		else
			config = new ConfigBuilder().withMasterUrl(masterURL)
					.withOauthToken(jsonClusterContext.getJSONArray("users").getJSONObject(0).getJSONObject("user")
							.getString("token"))
					.withCaCertData(jsonClusterContext.getJSONArray("clusters").getJSONObject(0)
							.getJSONObject("cluster").getString("certificate-authority-data"))
					.withTrustCerts(true).build();

		client = new DefaultKubernetesClient(config);
		return client;
	}

}
