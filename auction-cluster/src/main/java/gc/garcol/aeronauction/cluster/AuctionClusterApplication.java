package gc.garcol.aeronauction.cluster;

import gc.garcol.aeronauction.protocol.JvmFlagCheck;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of one cluster node.
 */
@SpringBootApplication
public class AuctionClusterApplication
{
    /**
     * Starts the node.
     *
     * @param args command line arguments.
     */
    public static void main(final String[] args)
    {
        JvmFlagCheck.requireAeronFlag();
        try
        {
            SpringApplication.run(AuctionClusterApplication.class, args);
        }
        catch (final RuntimeException ex)
        {
            // Spring has already logged this. Exit so the restart policy can retry: after a crash, Aeron refuses to
            // start until its old mark files have gone stale, and threads it already started would keep the JVM alive.
            System.exit(1);
        }
    }
}
