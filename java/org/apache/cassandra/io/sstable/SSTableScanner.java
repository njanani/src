/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.io.sstable;

import java.io.IOError;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.RowIndexEntry;
import org.apache.cassandra.db.RowPosition;
import org.apache.cassandra.db.columniterator.IColumnIterator;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.io.util.RandomAccessReader;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.CloseableIterator;

public class SSTableScanner implements CloseableIterator<IColumnIterator>
{
    private static final Logger logger = LoggerFactory.getLogger(SSTableScanner.class);

    protected final RandomAccessReader dfile;
    protected final RandomAccessReader ifile;
    public final SSTableReader sstable;
    private IColumnIterator row;
    protected boolean exhausted = false;
    protected Iterator<IColumnIterator> iterator;
    private final QueryFilter filter;

    /**
     * @param sstable SSTable to scan.
     */
    SSTableScanner(SSTableReader sstable, boolean skipCache)
    {
        try
        {
            this.dfile = sstable.openDataReader(skipCache);
            this.ifile = sstable.openIndexReader(skipCache);
        }
        catch (IOException e)
        {
            sstable.markSuspect();
            throw new IOError(e);
        }
        this.sstable = sstable;
        this.filter = null;
    }

    /**
     * @param sstable SSTable to scan.
     * @param filter filter to use when scanning the columns
     */
    SSTableScanner(SSTableReader sstable, QueryFilter filter)
    {
        try
        {
            this.dfile = sstable.openDataReader(false);
            this.ifile = sstable.openIndexReader(false);
        }
        catch (IOException e)
        {
            sstable.markSuspect();
            throw new IOError(e);
        }
        this.sstable = sstable;
        this.filter = filter;
    }

    public void close() throws IOException
    {
        FileUtils.close(dfile, ifile);
    }

    public void seekTo(RowPosition seekKey)
    {
        try
        {
            long indexPosition = sstable.getIndexScanPosition(seekKey);
            // -1 means the key is before everything in the sstable. So just start from the beginning.
            if (indexPosition == -1)
                indexPosition = 0;

            ifile.seek(indexPosition);

            while (!ifile.isEOF())
            {
                long startPosition = ifile.getFilePointer();
                DecoratedKey indexDecoratedKey = sstable.decodeKey(ByteBufferUtil.readWithShortLength(ifile));
                int comparison = indexDecoratedKey.compareTo(seekKey);
                if (comparison >= 0)
                {
                    // Found, just read the dataPosition and seek into index and data files
                    long dataPosition = ifile.readLong();
                    ifile.seek(startPosition);
                    dfile.seek(dataPosition);
                    row = null;
                    return;
                }
                else
                {
                    RowIndexEntry.serializer.skip(ifile, sstable.descriptor);
                }
            }
            exhausted = true;
        }
        catch (IOException e)
        {
            sstable.markSuspect();
            throw new RuntimeException("corrupt sstable", e);
        }
    }

    public long getFileLength()
    {
        try
        {
            return dfile.length();
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
    }

    public long getFilePointer()
    {
        return dfile.getFilePointer();
    }

    public boolean hasNext()
    {
        if (iterator == null)
            iterator = exhausted ? Arrays.asList(new IColumnIterator[0]).iterator() : createIterator();
        return iterator.hasNext();
    }

    public IColumnIterator next()
    {
        if (iterator == null)
            iterator = exhausted ? Arrays.asList(new IColumnIterator[0]).iterator() : createIterator();
        return iterator.next();
    }

    public void remove()
    {
        throw new UnsupportedOperationException();
    }

    private Iterator<IColumnIterator> createIterator()
    {
        return filter == null ? new KeyScanningIterator() : new FilteredKeyScanningIterator();
    }

    protected class KeyScanningIterator implements Iterator<IColumnIterator>
    {
        protected long finishedAt;

        public boolean hasNext()
        {
            try
            {
                if (row == null)
                    return !dfile.isEOF();
                return finishedAt < dfile.length();
            }
            catch (IOException e)
            {
                sstable.markSuspect();
                throw new RuntimeException(e);
            }
        }

        public IColumnIterator next()
        {
            try
            {
                if (row != null)
                    dfile.seek(finishedAt);
                assert !dfile.isEOF();

                // Read data header
                DecoratedKey key = sstable.decodeKey(ByteBufferUtil.readWithShortLength(dfile));
                long dataSize = SSTableReader.readRowSize(dfile, sstable.descriptor);
                long dataStart = dfile.getFilePointer();
                finishedAt = dataStart + dataSize;

                row = new SSTableIdentityIterator(sstable, dfile, key, dataStart, dataSize);
                return row;
            }
            catch (IOException e)
            {
                sstable.markSuspect();
                throw new RuntimeException(SSTableScanner.this + " failed to provide next columns from " + this, e);
            }
        }

        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "(" + "finishedAt:" + finishedAt + ")";
        }
    }

    protected class FilteredKeyScanningIterator implements Iterator<IColumnIterator>
    {
        protected DecoratedKey nextKey;
        protected RowIndexEntry nextEntry;

        public boolean hasNext()
        {
            try
            {
                if (row == null)
                    return !ifile.isEOF();
                return nextKey != null;
            }
            catch (IOException e)
            {
                sstable.markSuspect();
                throw new RuntimeException(e);
            }
        }

        public IColumnIterator next()
        {
            try
            {
                DecoratedKey currentKey;
                RowIndexEntry currentEntry;

                if (row == null)
                {
                    currentKey = sstable.decodeKey(ByteBufferUtil.readWithShortLength(ifile));
                    currentEntry = RowIndexEntry.serializer.deserialize(ifile, sstable.descriptor);
                }
                else
                {
                    currentKey = nextKey;
                    currentEntry = nextEntry;
                }

                if (ifile.isEOF())
                {
                    nextKey = null;
                    nextEntry = null;
                }
                else
                {
                    nextKey = sstable.decodeKey(ByteBufferUtil.readWithShortLength(ifile));
                    nextEntry = RowIndexEntry.serializer.deserialize(ifile, sstable.descriptor);
                }

                assert !dfile.isEOF();
                return row = filter.getSSTableColumnIterator(sstable, dfile, currentKey, currentEntry);
            }
            catch (IOException e)
            {
                sstable.markSuspect();
                throw new RuntimeException(SSTableScanner.this + " failed to provide next columns from " + this, e);
            }
        }

        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "(" +
               "dfile=" + dfile +
               " ifile=" + ifile +
               " sstable=" + sstable +
               " exhausted=" + exhausted +
               ")";
    }
}
