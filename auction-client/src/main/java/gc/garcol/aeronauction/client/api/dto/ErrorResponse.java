package gc.garcol.aeronauction.client.api.dto;

/**
 * Body of every error response.
 *
 * @param error what went wrong.
 */
public record ErrorResponse(String error)
{
}
