package gc.garcol.aeronauction.cluster;

import org.agrona.DeadlineTimerWheel;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongArrayList;

import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Auction deadlines on an Agrona timer wheel, driven only by timestamps from the replicated log.
 *
 * <p>{@link #advance} fires exactly the timers with {@code deadline <= now}, in auction ID order, regardless of the
 * wheel's internal tick. Replicas therefore agree even if one rebuilt its wheel from a snapshot at a different time.
 */
final class AuctionTimers
{
    /** Both must be powers of 2. Deadlines beyond one revolution (about 65s) simply wait in their spoke. */
    static final long RESOLUTION_MS = 128;
    static final int TICKS_PER_WHEEL = 512;

    private static final long NOT_FOUND = -1;

    private final DeadlineTimerWheel wheel =
        new DeadlineTimerWheel(TimeUnit.MILLISECONDS, 0, RESOLUTION_MS, TICKS_PER_WHEEL);
    private final Long2LongHashMap timerToAuction = new Long2LongHashMap(NOT_FOUND);
    private final LongArrayList expired = new LongArrayList();

    void schedule(final long auctionId, final long deadline, final long timestamp)
    {
        if (wheel.timerCount() == 0)
        {
            wheel.resetStartTime(timestamp); // nothing polled the wheel while it was empty, so it may be far behind
        }
        timerToAuction.put(wheel.scheduleTimer(deadline), auctionId);
    }

    void advance(final long now, final LongConsumer onExpired)
    {
        if (wheel.timerCount() == 0)
        {
            return; // an empty wheel may be far behind now, and schedule() resets it anyway
        }

        expired.clear();
        while (wheel.currentTickTime() <= now)
        {
            wheel.poll(now, this::collectExpired, Integer.MAX_VALUE); // one poll advances at most one tick
        }
        wheel.poll(now, this::collectExpired, Integer.MAX_VALUE); // required: visits the tick that contains now

        expired.sort(null);
        for (int i = 0, size = expired.size(); i < size; i++)
        {
            onExpired.accept(expired.getLong(i));
        }
    }

    long timerCount()
    {
        return wheel.timerCount();
    }

    private boolean collectExpired(final TimeUnit timeUnit, final long now, final long timerId)
    {
        expired.addLong(timerToAuction.remove(timerId));
        return true;
    }
}
