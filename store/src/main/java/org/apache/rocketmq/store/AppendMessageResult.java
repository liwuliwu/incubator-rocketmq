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
package org.apache.rocketmq.store;

/**
 * When write a message to the commit log, returns results
 */
public class AppendMessageResult {
    /**
     * Return code
     */
    private AppendMessageStatus status;
    /**
     * Where to start writing
     * PHY OFFSET
     */
    private long wroteOffset;
    /**
     * Write Bytes
     * 消息长度
     */
    private int wroteBytes;
    /**
     * Message ID
     * 消息编号
     */
    private String msgId;
    /**
     * Message storage timestamp
     * 存储时间
     */
    private long storeTimestamp;
    /**
     * Consume queue's offset(step by one)
     * 队列编号
     */
    @SuppressWarnings("SpellCheckingInspection")
    private long logicsOffset;
    /**
     * 写入buffer花费时间
     */
    @SuppressWarnings("SpellCheckingInspection")
    private long pagecacheRT = 0;

    public AppendMessageResult(AppendMessageStatus status) {
        this(status, 0, 0, "", 0, 0, 0);
    }

    //AppendMessageStatus status
    //追加结果（成功，到达文件尾（文件剩余空间不足）、消息长度超过、消息属性长度超出、未知错误）。
    //wroteOffset
    //消息的偏移量（相对于整个commitlog）。
    //wroteBytes 
    //消息待写入字节。
    //msgId
    //消息ID。
    //storeTimestamp
    //消息写入时间戳。
    //logicsOffset 
    //消息队列偏移量。
    //pagecacheRT 
    //消息写入时机戳（消息存储时间戳--- 消息存储开始时间戳）。
    //然后返回 AppendMessageStatus，流程回到 
    //【1.2.2 CommitLog.putMessage】的代码@6，如果返回结果是 OK 的话进入到代码@7，@8。
    public AppendMessageResult(AppendMessageStatus status, long wroteOffset, int wroteBytes, String msgId,
        long storeTimestamp, long logicsOffset, long pagecacheRT) {
        this.status = status;
        this.wroteOffset = wroteOffset;
        this.wroteBytes = wroteBytes;
        this.msgId = msgId;
        this.storeTimestamp = storeTimestamp;
        this.logicsOffset = logicsOffset;
        this.pagecacheRT = pagecacheRT;
    }

    public long getPagecacheRT() {
        return pagecacheRT;
    }

    public void setPagecacheRT(final long pagecacheRT) {
        this.pagecacheRT = pagecacheRT;
    }

    public boolean isOk() {
        return this.status == AppendMessageStatus.PUT_OK;
    }

    public AppendMessageStatus getStatus() {
        return status;
    }

    public void setStatus(AppendMessageStatus status) {
        this.status = status;
    }

    public long getWroteOffset() {
        return wroteOffset;
    }

    public void setWroteOffset(long wroteOffset) {
        this.wroteOffset = wroteOffset;
    }

    public int getWroteBytes() {
        return wroteBytes;
    }

    public void setWroteBytes(int wroteBytes) {
        this.wroteBytes = wroteBytes;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public long getStoreTimestamp() {
        return storeTimestamp;
    }

    public void setStoreTimestamp(long storeTimestamp) {
        this.storeTimestamp = storeTimestamp;
    }

    public long getLogicsOffset() {
        return logicsOffset;
    }

    public void setLogicsOffset(long logicsOffset) {
        this.logicsOffset = logicsOffset;
    }

    @Override
    public String toString() {
        return "AppendMessageResult{" +
            "status=" + status +
            ", wroteOffset=" + wroteOffset +
            ", wroteBytes=" + wroteBytes +
            ", msgId='" + msgId + '\'' +
            ", storeTimestamp=" + storeTimestamp +
            ", logicsOffset=" + logicsOffset +
            ", pagecacheRT=" + pagecacheRT +
            '}';
    }
}
