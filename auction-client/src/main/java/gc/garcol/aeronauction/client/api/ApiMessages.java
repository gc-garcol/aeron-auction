package gc.garcol.aeronauction.client.api;

/**
 * Response descriptions in the API documentation.
 */
final class ApiMessages
{
    static final String NOT_SENT = "The command was not sent, so it did not happen";
    static final String OUTCOME_UNKNOWN = "No reply in time. The command may still have applied, " +
        "so check with GET before retrying";
    static final String NOT_FOUND = "Unknown auction";

    private ApiMessages()
    {
    }
}
