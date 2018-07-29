package sb_playground;


import org.springframework.cloud.servicebroker.model.catalog.Catalog;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PKSCatalogConfig{
	private Catalog catalog;
	public Catalog Catalog() {
		return catalog;
	}
}