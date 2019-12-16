/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.List;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.store.MappedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexFile {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    // 每个 hash  槽所占的字节数
    private static int hashSlotSize = 4;
    // 每条indexFile条目占用字节数
    private static int indexSize = 20;
    // 用来验证是否是一个有效的索引。
    private static int invalidIndex = 0;
    // index 文件中 hash 槽的总个数
    private final int hashSlotNum;
    // indexFile中包含的条目数
    private final int indexNum;
    // 对应的映射文件
    private final MappedFile mappedFile;
    // 对应的文件通道
    private final FileChannel fileChannel;
    // 对应 PageCache
    private final MappedByteBuffer mappedByteBuffer;
    // IndexHeader,每一个indexfile的头部信息
    private final IndexHeader indexHeader;

    public IndexFile(final String fileName, final int hashSlotNum, final int indexNum,
        final long endPhyOffset, final long endTimestamp) throws IOException {
        int fileTotalSize =
            IndexHeader.INDEX_HEADER_SIZE + (hashSlotNum * hashSlotSize) + (indexNum * indexSize);
        this.mappedFile = new MappedFile(fileName, fileTotalSize);
        this.fileChannel = this.mappedFile.getFileChannel();
        this.mappedByteBuffer = this.mappedFile.getMappedByteBuffer();
        this.hashSlotNum = hashSlotNum;
        this.indexNum = indexNum;

        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        this.indexHeader = new IndexHeader(byteBuffer);

        if (endPhyOffset > 0) {
            this.indexHeader.setBeginPhyOffset(endPhyOffset);
            this.indexHeader.setEndPhyOffset(endPhyOffset);
        }

        if (endTimestamp > 0) {
            this.indexHeader.setBeginTimestamp(endTimestamp);
            this.indexHeader.setEndTimestamp(endTimestamp);
        }
    }

    public String getFileName() {
        return this.mappedFile.getFileName();
    }

    public void load() {
        this.indexHeader.load();
    }

    public void flush() {
        long beginTime = System.currentTimeMillis();
        if (this.mappedFile.hold()) {
            this.indexHeader.updateByteBuffer();
            this.mappedByteBuffer.force();
            this.mappedFile.release();
            log.info("flush index file eclipse time(ms) " + (System.currentTimeMillis() - beginTime));
        }
    }

    public boolean isWriteFull() {
        return this.indexHeader.getIndexCount() >= this.indexNum;
    }

    public boolean destroy(final long intervalForcibly) {
        return this.mappedFile.destroy(intervalForcibly);
    }

    //phyOffset 消息存储在commitlog的偏移量。
    //storeTimestamp  消息存入commitlog的时间戳。
    public boolean putKey(final String key, final long phyOffset, final long storeTimestamp) {//@1
        //代码@2：如果目前 index file 存储的条目数小于允许的条目数，则存入当前文件中，如果超出，则返回 false, 表示存入失败，IndexService 中有重试机制，默认重试3次。
        if (this.indexHeader.getIndexCount() < this.indexNum) {//@2
            int keyHash = indexKeyHashMethod(key);
            //从代码@3开始，主要是根据 IndexFile 的文件格式进行存储。
            //代码@3：先获取 key 的 hashcode，然后用 hashcode 和 hashSlotNum 取模，得到该 key 所在的 hashslot 下标，hashSlotNum默认500万个。
            int slotPos = keyHash % this.hashSlotNum;//@3
            //代码@4：根据 key 所算出来的 hashslot 的下标计算出绝对位置，从这里可以看出端倪：IndexFile的文件布局：文件头(IndexFileHeader 40个字节) + (hashSlotNum * 4)。
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;//@4

            FileLock fileLock = null;

            try {

                // fileLock = this.fileChannel.lock(absSlotPos, hashSlotSize,
                // false);
                //代码@5：读取 key 所在 hashslot 下标处的值(4个字节)，如果小于0或超过当前包含的 indexCount，则设置为0。
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);//@5
                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()) {
                    slotValue = invalidIndex;
                }

                long timeDiff = storeTimestamp - this.indexHeader.getBeginTimestamp();

                timeDiff = timeDiff / 1000;
                //代码@6：计算消息的存储时间与当前 IndexFile 存放的最小时间差额(单位为秒）。
                if (this.indexHeader.getBeginTimestamp() <= 0) {
                    timeDiff = 0;
                } else if (timeDiff > Integer.MAX_VALUE) {
                    timeDiff = Integer.MAX_VALUE;
                } else if (timeDiff < 0) {
                    timeDiff = 0;
                }//@6

                //代码@7：计算该 key 存放的条目的起始位置，等于=文件头(IndexFileHeader 40个字节) + (hashSlotNum * 4) + IndexSize(一个条目20个字节) *
                // 当前存放的条目数量。
                int absIndexPos =
                    IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                        + this.indexHeader.getIndexCount() * indexSize;//@7
                //代码@8：填充 IndexFile 条目，4字节（hashcode） + 8字节（commitlog offset） + 4字节
                // （commitlog存储时间与indexfile第一个条目的时间差，单位秒） + 4字节（同hashcode的上一个的位置，0表示没有上一个）。
                this.mappedByteBuffer.putInt(absIndexPos, keyHash);//@8
                this.mappedByteBuffer.putLong(absIndexPos + 4, phyOffset);
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8, (int) timeDiff);
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8 + 4, slotValue);

                //代码@9：将当前先添加的条目的位置，存入到 key hashcode 对应的 hash槽，
                //也就是该字段里面存放的是该 hashcode 最新的条目（如果产生hash冲突，不同的key，hashcode相同。
                this.mappedByteBuffer.putInt(absSlotPos, this.indexHeader.getIndexCount());//@9
                //代码@10：更新IndexFile头部相关字段，比如最小时间，当前最大时间等。
                if (this.indexHeader.getIndexCount() <= 1) {//@10
                    this.indexHeader.setBeginPhyOffset(phyOffset);
                    this.indexHeader.setBeginTimestamp(storeTimestamp);
                }

                this.indexHeader.incHashSlotCount();
                this.indexHeader.incIndexCount();
                this.indexHeader.setEndPhyOffset(phyOffset);
                this.indexHeader.setEndTimestamp(storeTimestamp);

                return true;
            } catch (Exception e) {
                log.error("putKey exception, Key: " + key + " KeyHashCode: " + key.hashCode(), e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            log.warn("Over index file capacity: index count = " + this.indexHeader.getIndexCount()
                + "; index max num = " + this.indexNum);
        }

        return false;
    }

    public int indexKeyHashMethod(final String key) {
        int keyHash = key.hashCode();
        int keyHashPositive = Math.abs(keyHash);
        if (keyHashPositive < 0)
            keyHashPositive = 0;
        return keyHashPositive;
    }

    public long getBeginTimestamp() {
        return this.indexHeader.getBeginTimestamp();
    }

    public long getEndTimestamp() {
        return this.indexHeader.getEndTimestamp();
    }

    public long getEndPhyOffset() {
        return this.indexHeader.getEndPhyOffset();
    }

    public boolean isTimeMatched(final long begin, final long end) {
        boolean result = begin < this.indexHeader.getBeginTimestamp() && end > this.indexHeader.getEndTimestamp();
        result = result || (begin >= this.indexHeader.getBeginTimestamp() && begin <= this.indexHeader.getEndTimestamp());
        result = result || (end >= this.indexHeader.getBeginTimestamp() && end <= this.indexHeader.getEndTimestamp());
        return result;
    }

    public void selectPhyOffset(final List<Long> phyOffsets, final String key, final int maxNum,
        final long begin, final long end, boolean lock) {//@1
        if (this.mappedFile.hold()) {
            int keyHash = indexKeyHashMethod(key);
            int slotPos = keyHash % this.hashSlotNum;
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;//@2

            FileLock fileLock = null;
            try {
                if (lock) {
                    // fileLock = this.fileChannel.lock(absSlotPos,
                    // hashSlotSize, true);
                }

                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                // if (fileLock != null) {
                // fileLock.release();
                // fileLock = null;
                // }

                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()
                    || this.indexHeader.getIndexCount() <= 1) {//@3
                    // TODO NOTFOUND
                } else {
                    for (int nextIndexToRead = slotValue;;) {//@4 开始循环找（相同hashcode的index条目是不连续的单向链表，最新的指向上一个。
                        if (phyOffsets.size() >= maxNum) {
                            break;
                        }

                        int absIndexPos =
                            IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                                + nextIndexToRead * indexSize;

                        int keyHashRead = this.mappedByteBuffer.getInt(absIndexPos);
                        long phyOffsetRead = this.mappedByteBuffer.getLong(absIndexPos + 4);

                        long timeDiff = (long) this.mappedByteBuffer.getInt(absIndexPos + 4 + 8);// 找到对应的条目
                        int prevIndexRead = this.mappedByteBuffer.getInt(absIndexPos + 4 + 8 + 4);

                        if (timeDiff < 0) {// 如果时间非法，则表示无效
                            break;
                        }

                        timeDiff *= 1000L;

                        long timeRead = this.indexHeader.getBeginTimestamp() + timeDiff;
                        boolean timeMatched = (timeRead >= begin) && (timeRead <= end);

                        if (keyHash == keyHashRead && timeMatched) {//@5
                            phyOffsets.add(phyOffsetRead);
                        }

                        if (prevIndexRead <= invalidIndex
                            || prevIndexRead > this.indexHeader.getIndexCount()
                            || prevIndexRead == nextIndexToRead || timeRead < begin) {
                            break;
                        }

                        nextIndexToRead = prevIndexRead;
                    }
                }
            } catch (Exception e) {
                log.error("selectPhyOffset exception ", e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                this.mappedFile.release();
            }
        }
    }
}
