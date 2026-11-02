package gc.garcol.aeronauction.client;

import gc.garcol.aeronauction.protocol.runtime.JvmFlagCheck;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the REST API.
 */
@OpenAPIDefinition(info = @Info(
    title = "Aeron Auction API",
    version = "0.0.1",
    description = "Auctions run on an Aeron Cluster. Amounts are whole numbers in minor units."))
@SpringBootApplication
public class AeronAuctionApplication
{

    /**
     * Starts the application.
     *
     * @param args command line arguments.
     */
    public static void main(final String[] args)
    {
        JvmFlagCheck.requireAeronFlag();
        SpringApplication.run(AeronAuctionApplication.class, args);
    }

}
