package pksServiceBroker;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

@Configuration
public class Config {
	static public enum BrokerAction {
		GET, CREATE, UPDATE, DELETE;
		private static final Map<String, BrokerAction> mappings = new HashMap<>(8);
		static {
			for (BrokerAction brokerAction : values()) {
				mappings.put(brokerAction.name(), brokerAction);
			}
		}

		@Nullable
		public static BrokerAction resolve(@Nullable String method) {
			return (method != null ? mappings.get(method) : null);
		}

		public boolean matches(String method) {
			return (this == resolve(method));
		}
	}

	static public enum RoutingLayer {
		HTTP, TCP;
		private static final Map<String, RoutingLayer> mappings = new HashMap<>(8);
		static {
			for (RoutingLayer routingLayer : values()) {
				mappings.put(routingLayer.name(), routingLayer);
			}
		}

		@Nullable
		public static RoutingLayer resolve(@Nullable String method) {
			return (method != null ? mappings.get(method) : null);
		}

		public boolean matches(String method) {
			return (this == resolve(method));
		}
	}

	static final String BAZAAR_NAME = "kibosh-bazaar";
	static final String KIBOSH_NAME = "kibosh";

	static final int KUBERNETES_MASTER_PORT = 8443;
	static final int KIBOSH_INTERNAL_PORT = 8080;
	static final int BAZAAR_INTERNAL_PORT = 8080;

	@Value("${pks.fqdn}")
	String PKS_FQDN;
	@Value("${pcf.tcp}")
	String TCP_FQDN;
	@Value("${pcf.apps}")
	String APPS_FQDN;
	@Value("${pcf.api}")
	String PCF_API;

	static final String ADDON_NAMESPACE = "kube-system";
	static final String ROUTE_DEPLOYMENT_PREFIX = "tcp-route-registrar-";
	static final int ROUTE_TTL = 20;

}
