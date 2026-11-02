package gc.garcol.aeronauction.protocol.runtime;

/**
 * Aeron fails on Java 25 without a JVM flag, and with an error that does not say so. An application cannot add the
 * flag to itself, so this stops at startup with a message that says what to do.
 */
public final class JvmFlagCheck
{
    private static final String INTERNAL_PACKAGE = "jdk.internal.misc";
    private static final String REQUIRED_FLAG = "--add-opens java.base/jdk.internal.misc=ALL-UNNAMED";

    private JvmFlagCheck()
    {
    }

    /**
     * Exits with status 1 if the flag is missing. Call this first in {@code main}.
     */
    public static void requireAeronFlag()
    {
        if (!Object.class.getModule().isOpen(INTERNAL_PACKAGE, JvmFlagCheck.class.getModule()))
        {
            System.err.println("Missing JVM option: " + REQUIRED_FLAG);
            System.err.println("In IntelliJ, run the \"aeron-auction (dev)\" configuration, or add it to VM options.");
            System.exit(1);
        }
    }
}
