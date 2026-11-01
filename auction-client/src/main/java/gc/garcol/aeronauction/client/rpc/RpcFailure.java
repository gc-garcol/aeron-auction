package gc.garcol.aeronauction.client.rpc;

/** Why a command got no reply from the cluster. */
public final class RpcFailure extends RuntimeException
{
    /**
     * The kinds of failure.
     */
    public enum Kind
    {
        /** The command was not sent, so it definitely did not happen. */
        UNAVAILABLE,
        /** The command was sent but no reply arrived, so it may still have happened. */
        TIMEOUT
    }

    private final Kind kind;

    RpcFailure(final Kind kind, final String message)
    {
        super(message, null, false, false);
        this.kind = kind;
    }

    /**
     * Says what went wrong.
     *
     * @return the kind of failure.
     */
    public Kind kind()
    {
        return kind;
    }
}
