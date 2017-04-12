/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.io.pagecache.impl.muninn;

import java.io.IOException;

import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PageSwapper;
import org.neo4j.io.pagecache.tracing.EvictionEvent;
import org.neo4j.io.pagecache.tracing.EvictionEventOpportunity;
import org.neo4j.io.pagecache.tracing.FlushEvent;
import org.neo4j.io.pagecache.tracing.PageFaultEvent;
import org.neo4j.unsafe.impl.internal.dragons.MemoryManager;
import org.neo4j.unsafe.impl.internal.dragons.UnsafeUtil;

import static java.lang.String.format;

/**
 * The PageList maintains the off-heap meta-data for the individual memory pages.
 *
 * The meta-data for each page is the following:
 *
 * <table>
 *     <tr><th>Bytes</th><th>Use</th></tr>
 *     <tr><td>8</td><td>Sequence lock word.</td></tr>
 *     <tr><td>8</td><td>Pointer to the memory page.</td></tr>
 *     <tr><td>8</td><td>File page id.</td></tr>
 *     <tr><td>4</td><td>Page swapper id.</td></tr>
 *     <tr><td>1</td><td>Usage stamp. Optimistically incremented; truncated to a max of 4.</td></tr>
 *     <tr><td>3</td><td>Padding.</td></tr>
 * </table>
 */
class PageList
{
    private static final int META_DATA_BYTES_PER_PAGE = 32;
    private static final int OFFSET_LOCK_WORD = 0; // 8 bytes
    private static final int OFFSET_ADDRESS = 8; // 8 bytes
    private static final int OFFSET_FILE_PAGE_ID = 16; // 8 bytes
    private static final int OFFSET_SWAPPER_ID = 24; // 4 bytes
    private static final int OFFSET_USAGE_COUNTER = 28; // 1 byte
    // todo it's possible to reduce the overhead of the individual page to just 24 bytes,
    // todo because the file page id can be represented with 5 bytes (enough to address 8-4 PBs),
    // todo and then the usage counter can use the high bits of that word, and the swapper id
    // todo can use the rest (2 bytes or 20 bits).
    // todo we can alternatively also make use of the lower 12 bits of the address field, because
    // todo the addresses are page aligned, and we can assume them to be at least 4096 bytes in size.

    // xxx Thinking about it some more, it might even be possible to get down to just 16 bytes per page.
    // xxx We cannot change the lock word, but if we change the eviction scheme to always go through the translation
    // xxx tables to find pages, then we won't need the file page id. The address ought to have its lower 13 bits free.
    // xxx Two of these can go to the usage counter, and the rest to the swapper id, for up to 2048 swappers.
    // xxx Do we even need the swapper id at this point? If we can already infer the file page id, the same should be
    // xxx true for the swapper.
    // xxx The trouble with this idea is that we won't be able to seamlessly turn the translation tables into hash
    // xxx tables when they get too big - at least not easily - since the index into the table no longer corresponds to
    // xxx the file page id. We might still be able to get away with it, if with the page id we also keep a counter for
    // xxx how many times the file page id loop around the translation table before arriving at the given entry.
    // xxx That is, what the entry index should be multiplied by to get the file page id. To do this, we would, however,
    // xxx have to either grab bits from the page id space, or make each entry more than 4 bytes. This will all depend
    // xxx on where the cut-off point is. That is, what the max entry capacity for a translation table should be.
    // xxx One potential cut-off point could be 2^29. That many 4 byte translation table entries would take up 2 GiB.
    // xxx If we can then somehow put the wrap-around counter in the page meta-data by, for instance, taking a byte
    // xxx from the bits in the address that we are no longer using for the swapper id, then we can support 255
    // xxx wrap-arounds with bits to spare. This will allow us to address files that are up to 1 peta-byte in size.
    // xxx At such extremes we'd only be able to keep up to 1/256th of the file in memory, which is 4 TiB, which in
    // xxx turn is 1/8th of the 8192 * 2^32 = 32 TiB max memory capacity. To increase the potential utilisation, we can
    // xxx raise the cut-off point to up to 2^32, which would require 16 GiBs of memory to represent.
    // xxx Since we know up front how many pages we have at our disposal, and that 32 TiB of RAM is far from common,
    // xxx we can place our preferred cut-off point at one or two bit-widths higher than the required bits to address
    // xxx the memory. This would keep the risk of collisions down. Hopefully to a somewhat reasonable level.

    private final int pageCount;
    private final int cachePageSize;
    private final MemoryManager memoryManager;
    private final SwapperSet swappers;
    private final long victimPageAddress;
    private final long baseAddress;

    PageList( int pageCount, int cachePageSize, MemoryManager memoryManager, SwapperSet swappers, long victimPageAddress )
    {
        this.pageCount = pageCount;
        this.cachePageSize = cachePageSize;
        this.memoryManager = memoryManager;
        this.swappers = swappers;
        this.victimPageAddress = victimPageAddress;
        long bytes = pageCount * META_DATA_BYTES_PER_PAGE;
        this.baseAddress = memoryManager.allocateAligned( bytes );
        clearMemory( baseAddress, pageCount );
    }

    /**
     * This copy-constructor is useful for classes that want to extend the {@code PageList} class to inline its fields.
     * All data and state will be shared between this and the given {@code PageList}. This means that changing the page
     * list state through one has the same effect as changing it through the other – they are both effectively the same
     * object.
     * @param pageList The {@code PageList} instance whose state to copy.
     */
    PageList( PageList pageList )
    {
        this.pageCount = pageList.pageCount;
        this.cachePageSize = pageList.cachePageSize;
        this.memoryManager = pageList.memoryManager;
        this.swappers = pageList.swappers;
        this.victimPageAddress = pageList.victimPageAddress;
        this.baseAddress = pageList.baseAddress;
    }

    private void clearMemory( long baseAddress, long pageCount )
    {
        long address = baseAddress - 8;
        for ( long i = 0; i < pageCount; i++ )
        {
            UnsafeUtil.putLong( address += 8, OffHeapPageLock.initialLockWordWithExclusiveLock() ); // lock word
            UnsafeUtil.putLong( address += 8, 0 ); // pointer
            UnsafeUtil.putLong( address += 8, PageCursor.UNBOUND_PAGE_ID ); // file page id
            UnsafeUtil.putLong( address += 8, 0 ); // rest
        }
        UnsafeUtil.fullFence(); // Guarantee the visibility of the cleared memory
    }

    /**
     * @return The capacity of the page list.
     */
    public int getPageCount()
    {
        return pageCount;
    }

    public SwapperSet getSwappers()
    {
        return swappers;
    }

    /**
     * Turn a {@code pageId} into a {@code pageRef} that can be used for accessing and manipulating the given page
     * using the other methods in this class.
     * @param pageId The {@code pageId} to turn into a {@code pageRef}.
     * @return A {@code pageRef} which is an opaque, internal and direct pointer to the meta-data of the given memory
     * page.
     */
    public long deref( int pageId )
    {
        //noinspection UnnecessaryLocalVariable
        long id = pageId; // convert to long to avoid int multiplication
        return baseAddress + (id * META_DATA_BYTES_PER_PAGE);
    }

    public int toId( long pageRef )
    {
        // >> 5 is equivalent to dividing by 32, META_DATA_BYTES_PER_PAGE.
        return (int) ((pageRef - baseAddress) >> 5);
    }

    private long offLock( long pageRef )
    {
        return pageRef + OFFSET_LOCK_WORD;
    }

    private long offAddress( long pageRef )
    {
        return pageRef + OFFSET_ADDRESS;
    }

    private long offUsage( long pageRef )
    {
        return pageRef + OFFSET_USAGE_COUNTER;
    }

    private long offFilePageId( long pageRef )
    {
        return pageRef + OFFSET_FILE_PAGE_ID;
    }

    private long offSwapperId( long pageRef )
    {
        return pageRef + OFFSET_SWAPPER_ID;
    }

    public long tryOptimisticReadLock( long pageRef )
    {
        return OffHeapPageLock.tryOptimisticReadLock( offLock( pageRef ) );
    }

    public boolean validateReadLock( long pageRef, long stamp )
    {
        return OffHeapPageLock.validateReadLock( offLock( pageRef ), stamp );
    }

    public boolean isModified( long pageRef )
    {
        return OffHeapPageLock.isModified( offLock( pageRef ) );
    }

    public boolean isExclusivelyLocked( long pageRef )
    {
        return OffHeapPageLock.isExclusivelyLocked( offLock( pageRef ) );
    }

    public boolean tryWriteLock( long pageRef )
    {
        return OffHeapPageLock.tryWriteLock( offLock( pageRef ) );
    }

    public void unlockWrite( long pageRef )
    {
        OffHeapPageLock.unlockWrite( offLock( pageRef ) );
    }

    public long unlockWriteAndTryTakeFlushLock( long pageRef )
    {
        return OffHeapPageLock.unlockWriteAndTryTakeFlushLock( offLock( pageRef ) );
    }

    public boolean tryExclusiveLock( long pageRef )
    {
        return OffHeapPageLock.tryExclusiveLock( offLock( pageRef ) );
    }

    public long unlockExclusive( long pageRef )
    {
        return OffHeapPageLock.unlockExclusive( offLock( pageRef ) );
    }

    public void unlockExclusiveAndTakeWriteLock( long pageRef )
    {
        OffHeapPageLock.unlockExclusiveAndTakeWriteLock( offLock( pageRef ) );
    }

    public long tryFlushLock( long pageRef )
    {
        return OffHeapPageLock.tryFlushLock( offLock( pageRef ) );
    }

    public void unlockFlush( long pageRef, long stamp, boolean success )
    {
        OffHeapPageLock.unlockFlush( offLock( pageRef ), stamp, success );
    }

    public void explicitlyMarkPageUnmodifiedUnderExclusiveLock( long pageRef )
    {
        OffHeapPageLock.explicitlyMarkPageUnmodifiedUnderExclusiveLock( offLock( pageRef ) );
    }

    public int getCachePageSize()
    {
        return cachePageSize;
    }

    public long getAddress( long pageRef )
    {
        return UnsafeUtil.getLong( offAddress( pageRef ) );
    }

    public void initBuffer( long pageRef )
    {
        if ( getAddress( pageRef ) == 0L )
        {
            long addr = memoryManager.allocateAligned( getCachePageSize() );
            UnsafeUtil.putLong( offAddress( pageRef ), addr );
        }
    }

    private byte getUsageCounter( long pageRef )
    {
        return UnsafeUtil.getByteVolatile( offUsage( pageRef ) );
    }

    private void setUsageCounter( long pageRef, byte count )
    {
        UnsafeUtil.putByteVolatile( offUsage( pageRef ), count );
    }

    /**
     * Increment the usage stamp to at most 4.
     **/
    public void incrementUsage( long pageRef )
    {
        // This is intentionally left benignly racy for performance.
        byte usage = getUsageCounter( pageRef );
        if ( usage < 4 ) // avoid cache sloshing by not doing a write if counter is already maxed out
        {
            usage++;
            setUsageCounter( pageRef, usage );
        }
    }

    /**
     * Decrement the usage stamp. Returns true if it reaches 0.
     **/
    public boolean decrementUsage( long pageRef )
    {
        // This is intentionally left benignly racy for performance.
        byte usage = getUsageCounter( pageRef );
        if ( usage > 0 )
        {
            usage--;
            setUsageCounter( pageRef, usage );
        }
        return usage == 0;
    }

    public long getFilePageId( long pageRef )
    {
        return UnsafeUtil.getLong( offFilePageId( pageRef ) );
    }

    private void setFilePageId( long pageRef, long filePageId )
    {
        UnsafeUtil.putLong( offFilePageId( pageRef ), filePageId );
    }

    public int getSwapperId( long pageRef )
    {
        return UnsafeUtil.getInt( offSwapperId( pageRef ) );
    }

    private void setSwapperId( long pageRef, int swapperId )
    {
        UnsafeUtil.putInt( offSwapperId( pageRef ), swapperId );
    }

    public boolean isLoaded( long pageRef )
    {
        return getFilePageId( pageRef ) != PageCursor.UNBOUND_PAGE_ID;
    }

    public boolean isBoundTo( long pageRef, int swapperId, long filePageId )
    {
        return getSwapperId( pageRef ) == swapperId && getFilePageId( pageRef ) == filePageId;
    }

    public void fault( long pageRef, PageSwapper swapper, int swapperId, long filePageId, PageFaultEvent event )
            throws IOException
    {
        if ( swapper == null )
        {
            throw swapperCannotBeNull();
        }
        int currentSwapper = getSwapperId( pageRef );
        long currentFilePageId = getFilePageId( pageRef );
        if ( filePageId == PageCursor.UNBOUND_PAGE_ID || !isExclusivelyLocked( pageRef )
             || currentSwapper != 0 || currentFilePageId != PageCursor.UNBOUND_PAGE_ID )
        {
            throw cannotFaultException( pageRef, swapper, swapperId, filePageId, currentSwapper, currentFilePageId );
        }
        // Note: It is important that we assign the filePageId before we swap
        // the page in. If the swapping fails, the page will be considered
        // loaded for the purpose of eviction, and will eventually return to
        // the freelist. However, because we don't assign the swapper until the
        // swapping-in has succeeded, the page will not be considered bound to
        // the file page, so any subsequent thread that finds the page in their
        // translation table will re-do the page fault.
        setFilePageId( pageRef, filePageId ); // Page now considered isLoaded()
        long bytesRead = swapper.read( filePageId, getAddress( pageRef ), cachePageSize );
        event.addBytesRead( bytesRead );
        event.setCachePageId( toId( pageRef ) );
        setSwapperId( pageRef, swapperId ); // Page now considered isBoundTo( swapper, filePageId )
    }

    private static IllegalArgumentException swapperCannotBeNull()
    {
        return new IllegalArgumentException( "swapper cannot be null" );
    }

    private static IllegalStateException cannotFaultException( long pageRef, PageSwapper swapper, int swapperId,
                                                        long filePageId, int currentSwapper, long currentFilePageId )
    {
        String msg = format(
                "Cannot fault page {filePageId = %s, swapper = %s (swapper id = %s)} into " +
                "cache page %s. Already bound to {filePageId = " +
                "%s, swapper id = %s}.",
                filePageId, swapper, swapperId, pageRef, currentFilePageId, currentSwapper );
        return new IllegalStateException( msg );
    }

    public boolean tryEvict( long pageRef, EvictionEventOpportunity evictionOpportunity ) throws IOException
    {
        if ( tryExclusiveLock( pageRef ) )
        {
            if ( isLoaded( pageRef ) )
            {
                try ( EvictionEvent evictionEvent = evictionOpportunity.beginEviction() )
                {
                    evict( pageRef, evictionEvent );
                    return true;
                }
            }
            unlockExclusive( pageRef );
        }
        return false;
    }

    private void evict( long pageRef, EvictionEvent evictionEvent ) throws IOException
    {
        long filePageId = getFilePageId( pageRef );
        evictionEvent.setFilePageId( filePageId );
        evictionEvent.setCachePageId( pageRef );
        int swapperId = getSwapperId( pageRef );
        if ( swapperId != 0 )
        {
            // If the swapper id is non-zero, then the page was not only loaded, but also bound, and possibly modified.
            PageSwapper swapper = swappers.getAllocation( swapperId ).swapper;
            evictionEvent.setSwapper( swapper );

            if ( isModified( pageRef ) )
            {
                FlushEvent flushEvent = evictionEvent.flushEventOpportunity().beginFlush( filePageId, pageRef, swapper );
                try
                {
                    long address = getAddress( pageRef );
                    long bytesWritten = swapper.write( filePageId, address );
                    explicitlyMarkPageUnmodifiedUnderExclusiveLock( pageRef );
                    flushEvent.addBytesWritten( bytesWritten );
                    flushEvent.addPagesFlushed( 1 );
                    flushEvent.done();
                }
                catch ( IOException e )
                {
                    unlockExclusive( pageRef );
                    flushEvent.done( e );
                    evictionEvent.threwException( e );
                    throw e;
                }
            }
            swapper.evicted( filePageId );
        }
        clearBinding( pageRef );
    }

    private void clearBinding( long pageRef )
    {
        setFilePageId( pageRef, PageCursor.UNBOUND_PAGE_ID );
        setSwapperId( pageRef, 0 );
    }

    public String toString( long pageRef )
    {
        StringBuilder sb = new StringBuilder();
        toString( pageRef, sb );
        return sb.toString();
    }

    public void toString( long pageRef, StringBuilder sb )
    {
        sb.append( "Page[ id = " ).append( toId( pageRef ) );
        sb.append( ", address = " ).append( getAddress( pageRef ) );
        sb.append( ", filePageId = " ).append( getFilePageId( pageRef ) );
        sb.append( ", swapperId = " ).append( getSwapperId( pageRef ) );
        sb.append( ", usageCounter = " ).append( getUsageCounter( pageRef ) );
        sb.append( " ] " ).append( OffHeapPageLock.toString( offLock( pageRef ) ) );
    }
}
