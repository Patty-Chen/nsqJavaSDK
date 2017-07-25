package com.youzan.nsq.client.core;

import com.youzan.nsq.client.core.command.*;
import com.youzan.nsq.client.entity.Address;
import com.youzan.nsq.client.entity.NSQConfig;
import com.youzan.nsq.client.entity.NSQMessage;
import com.youzan.nsq.client.entity.Topic;
import com.youzan.nsq.client.network.frame.ErrorFrame;
import com.youzan.nsq.client.network.frame.NSQFrame;
import com.youzan.nsq.client.network.frame.ResponseFrame;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author <a href="mailto:my_email@email.exmaple.com">zhaoxi (linzuxiong)</a>
 */
public class NSQConnectionImpl implements Serializable, NSQConnection, Comparable {
    private static final Logger logger = LoggerFactory.getLogger(NSQConnectionImpl.class);
    private static final long serialVersionUID = 7139923487863469738L;

    private final ReentrantReadWriteLock conLock = new ReentrantReadWriteLock();
    private final int id; // primary key
    private final long queryTimeoutInMillisecond;

    private AtomicBoolean closing = new AtomicBoolean(Boolean.FALSE);
    private AtomicBoolean identitySent = new AtomicBoolean(Boolean.FALSE);
    private AtomicBoolean backoff = new AtomicBoolean(Boolean.FALSE);

    protected final LinkedBlockingQueue<NSQCommand> requests = new LinkedBlockingQueue<>(1);
    protected final LinkedBlockingQueue<NSQFrame> responses = new LinkedBlockingQueue<>(1);

    private final Address address;
    protected final Channel channel;
    //topic for subscribe
    private Topic topic;
    private final NSQConfig config;

    //indicate if current should be extensible, if it is true, message received from nsqd should be extended.
    private final boolean isExtend;

    //start ready cnt for current count
    private AtomicInteger currentRdy = new AtomicInteger(1);
    private AtomicInteger lastRdy = new AtomicInteger(1);
    private AtomicInteger expectedRdy = new AtomicInteger(1);

    private final AtomicLong latestInternalID = new AtomicLong(-1L);
    private final AtomicLong latestDiskQueueOffset = new AtomicLong(-1L);

    private volatile long lastMsgReceived;
    private volatile long lastMsgConsumptionFailed;

    public NSQConnectionImpl(int id, Address address, Channel channel, NSQConfig config) {
        this.id = id;
        this.address = address;
        this.channel = channel;
        this.config = config;
        this.currentRdy.set(1);
        this.expectedRdy.set(this.config.getRdy());
        this.queryTimeoutInMillisecond = config.getQueryTimeoutInMillisecond();
        if(address.isTopicExtend()) {
            isExtend = Boolean.TRUE;
        } else {
            isExtend = Boolean.FALSE;
        }
        if(logger.isDebugEnabled())
            logger.debug("extend marked as {} for connection to {}", this.isExtend, address);
    }

    @Override
    public boolean isExtend() {
        return this.isExtend;
    }

    @Override
    public boolean checkOrder(long internalID, long diskQueueOffset, final NSQMessage msg){
        if(!this.config.isOrdered())
            return true;
        if(internalID >= this.latestInternalID.get() && diskQueueOffset >= this.latestDiskQueueOffset.get()){
            this.latestInternalID.set(internalID);
            this.latestDiskQueueOffset.set(diskQueueOffset);
            return true;
        }else {
            logger.warn("InternalID or diskQueueOffset is(are) NOT latest in current connection.\n" +
                    "InternalID:{}, latestInternalID:{}. diskQueueOffset:{}, latestQueueOffset:{}.\n" +
                    "Message: {}.", internalID, diskQueueOffset, this.latestInternalID.get(), this.latestDiskQueueOffset.get(), msg.toMetadataStr());
            return false;
        }
    }

    /**
     * initialize NSQConnection to NSQd by sending Identify Command
     */
    @Override
    public void init() throws TimeoutException {
        conLock.writeLock().lock();
        try {
           _init();
        } finally {
            conLock.writeLock().unlock();
        }
    }

    @Override
    public void init(final Topic topic) throws TimeoutException {
        conLock.writeLock().lock();
        try {
            this._init();
            Topic topicCon = Topic.newInstacne(topic, true);
            setTopic(topicCon);
        }finally {
            conLock.writeLock().unlock();
        }
    }

    private void _init() throws TimeoutException {
        assert address != null;
        assert config != null;
        if (identitySent.compareAndSet(Boolean.FALSE, Boolean.TRUE)) {
            command(Magic.getInstance());
            final NSQCommand identify = new Identify(config);
            NSQFrame response = null;
            try {
                response = _commandAndGetResposne(identify);
            } catch (InterruptedException e) {
                logger.error("Interrupted waiting for identity from {}", this.address);
            }
            if (null == response) {
                throw new IllegalStateException("Bad Identify Response! Close connection!");
            }
        }
        assert channel.isActive();
        if(logger.isDebugEnabled())
            logger.debug("Having initiated {}", this);
    }

    @Override
    public ChannelFuture command(NSQCommand cmd) {
        if (cmd == null) {
            return null;
        }

        // Use Netty Pipeline
        return channel.writeAndFlush(cmd);
    }

    private NSQFrame _commandAndGetResposne(final NSQCommand command) throws TimeoutException, InterruptedException {
        final long start = System.currentTimeMillis();
        long timeout = queryTimeoutInMillisecond - (System.currentTimeMillis() - start);
        if (!requests.offer(command, timeout, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException(
                    "The command timeout in " + timeout + " milliSec. The command name is : " + command.getClass().getName());
        }

        responses.clear(); // clear
        // write data
        final ChannelFuture future = command(command);

        // wait to get the response
        timeout = queryTimeoutInMillisecond - (System.currentTimeMillis() - start);
        if (!future.await(timeout, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException(
                    "The command timeout in " + timeout + " milliSec. The command name is : " + command.getClass().getName());
        }
        timeout = queryTimeoutInMillisecond - (System.currentTimeMillis() - start);
        final NSQFrame frame = responses.poll(timeout, TimeUnit.MILLISECONDS);
        if (frame == null) {
            throw new TimeoutException(
                    "The command timeout in " + timeout + " milliSec. The command name is : " + command.getClass().getName());
        }

        requests.poll(); // clear
        return frame;
    }

    @Override
    public NSQFrame commandAndGetResponse(final NSQCommand command) throws TimeoutException {
        if (!channel.isActive()) {
            if (!closing.get()) {
                throw new TimeoutException("The channel " + channel + " is closed. This is not closing.");
            } else {
                throw new TimeoutException("The channel " + channel + " is closed. This is closing.");
            }
        } else if(closing.get()) {
            logger.info("NSQConnection is closing... command quite");
        }
        conLock.readLock().lock();
        try {
           return _commandAndGetResposne(command);
        } catch (InterruptedException e) {
            _close();
            Thread.currentThread().interrupt();
            logger.error("Thread was interrupted, probably shutting down! Close connection!", e);
        } finally {
            conLock.readLock().unlock();
        }
        return null;
    }

    @Override
    public void addResponseFrame(ResponseFrame frame) {
        if (!requests.isEmpty()) {
            try {
                responses.offer(frame, queryTimeoutInMillisecond * 2, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                close();
                Thread.currentThread().interrupt();
                logger.error("Thread was interrupted, probably shutting down!", e);
            }
        } else {
            logger.error("No request to send, but get a frame from the server.");
        }
    }

    @Override
    public void addErrorFrame(ErrorFrame frame) {
        try {
            responses.offer(frame, queryTimeoutInMillisecond, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Thread was interrupted, probably shutting down!", e);
        }
    }

    protected void setTopic(final Topic topic) {
        this.topic = topic;
    }

    @Override
    public Topic getTopic() {
        return this.topic;
    }

    @Override
    public boolean isConnected() {
        conLock.readLock().lock();
        try {
            return channel.isActive() && !closing.get();
        }finally {
            conLock.readLock().unlock();
        }
    }

    @Override
    public boolean isIdentitySent() {
        return identitySent.get();
    }

    /**
     * clear underneath resources of {@Link NSQConnection}
     */
    @Override
    public void close() {
        logger.info("Begin to clear {}", this);
        conLock.writeLock().lock();
        try {
           _close();
        } finally {
            conLock.writeLock().unlock();
        }
    }

    private void _close() {
        if (null != channel) {
            channel.attr(NSQConnection.STATE).remove();
            channel.attr(Client.STATE).remove();
            if(channel.hasAttr(Client.ORDERED))
                channel.attr(Client.ORDERED).remove();
            if (channel.isActive()) {
                channel.close();
                channel.deregister();
            }
            if (!channel.isActive()) {
                logger.info("Having cleared {} OK!", this);
            }
        } else {
            logger.error("No channel has be set...");
        }
    }

    /**
     * disconnection current NSQConnection from nsqd, including
     * 1. backoff
     * 2. Send CLS command
     * 3. clear resources underneath
     */
    public void disconnect(final ConnectionManager conMgr) {
        conLock.writeLock().lock();
        try {
            logger.info("Disconnect from nsqd {} ...", this.address);
            //1. backoff
            conMgr.backoff(this);
            //2. send CLS
            this._onClose();
        } finally {
            //3. clear resource
            if (channel.isActive())
                this._close();
            logger.info("nsqd {} disconnect", this.address);
            conLock.writeLock().unlock();
        }
    }

    @Override
    public void onRdy(final int rdy, final IRdyCallback callback) {
        if(!this.isConnected()) {
            logger.info("Connection is closed. Resume quit. {}", this);
            int currentRdy = getCurrentRdyCount();
            callback.onUpdated(currentRdy, currentRdy);
            return;
        }

        if(backoff.get()) {
            logger.info("Connection is already backed off. {}", this);
            int currentRdy = getCurrentRdyCount();
            callback.onUpdated(currentRdy, currentRdy);
            return;
        }

        command(new Rdy(rdy)).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if(channelFuture.isSuccess()) {
                    int lastRdy = getCurrentRdyCount();
                    setCurrentRdyCount(rdy);
                    callback.onUpdated(rdy, lastRdy);
                } else {
                    logger.warn("Fail to update Rdy for connection {}", this);
                }
            }
        });
    }

    public boolean isBackoff() {
        return this.backoff.get();
    }

    @Override
    public void onClose() {
        conLock.writeLock().lock();
        try {
           _onClose();
        }finally {
            conLock.writeLock().unlock();
        }
    }

    private void _onClose() {
        //closing signal is updated here
        if (identitySent.compareAndSet(Boolean.TRUE, Boolean.FALSE) && closing.compareAndSet(Boolean.FALSE, Boolean.TRUE)) {
            try {
                this._commandAndGetResposne(Close.getInstance());
            } catch (TimeoutException e) {
                logger.warn("Timeout receiving response for Close command.");
            } catch (InterruptedException e) {
                logger.error("Interrupted waiting for response from CLS.");
            }
        }
    }

    @Override
    public void onResume(final IRdyCallback callback) {
        if(!this.isConnected()) {
            logger.info("Connection is closed. Resume quit. {}", this);
            int currentRdy = getCurrentRdyCount();
            callback.onUpdated(currentRdy, currentRdy);
            return;
        }
        if(backoff.compareAndSet(true, false)) {
            final int rdy = this.lastRdy.get();
            command(new Rdy(rdy)).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if(channelFuture.isSuccess()) {
                        int lastRdy = getCurrentRdyCount();
                        setCurrentRdyCount(rdy);
                        callback.onUpdated(rdy, lastRdy);
                    } else {
                        logger.warn("Fail to resume consumption for connection {}", this);
                    }
                }
            });
        } else {
            logger.info("Connection is not backed off. {}", this);
            int currentRdy = getCurrentRdyCount();
            callback.onUpdated(currentRdy, currentRdy);
        }
    }

    @Override
    public void onBackoff(final IRdyCallback callback) {
        if(!this.isConnected()) {
            logger.info("Connection is closed. Back off quit. {}", this);
            callback.onUpdated(0, 0);
            return;
        }
        if(backoff.compareAndSet(false,true)) {
            //update last rdy
            command(Rdy.BACK_OFF).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if (channelFuture.isSuccess()) {
                        int lastRdy = getCurrentRdyCount();
                        setCurrentRdyCount(0);
                        callback.onUpdated(0, lastRdy);
                    } else {
                        logger.warn("Fail to backoff consumption for connection {}", this);
                    }
                }
            });
        } else {
            logger.info("Connection is already backed off. {}", this);
            //notify callback with new rdy and old rdy
            callback.onUpdated(0, 0);
        }
    }

    public synchronized void setCurrentRdyCount(int newCount) {
        if(newCount < 0 || this.currentRdy.get() == newCount) {
            if(newCount == 0)
                logger.info("Backoff connection {}", this);
            return;
        }
        this.lastRdy.set(this.currentRdy.get());
        this.currentRdy.set(newCount);
    }

    public synchronized int declineExpectedRdy() {
        if(this.expectedRdy.get() - 1 >= 0)
            return this.expectedRdy.decrementAndGet();
        return this.expectedRdy.get();
    }

    public synchronized int increaseExpectedRdy() {
        if(this.expectedRdy.get() + 1 <= this.config.getRdy())
            return this.expectedRdy.incrementAndGet();
        return this.expectedRdy.get();
    }

    public int getExpectedRdy() {
        return this.expectedRdy.get();
    }

    public int getCurrentRdyCount() {
        return this.currentRdy.get();
    }
    /**
     * @return the id , the primary key of the object
     */
    @Override
    public int getId() {
        return id;
    }

    /**
     * @return the address
     */
    @Override
    public Address getAddress() {
        return address;
    }

    @Override
    public NSQConfig getConfig() {
        return config;
    }


    @Override
    public int compareTo(Object o) {
        return getId() - ((NSQConnectionImpl) o).getId();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NSQConnectionImpl that = (NSQConnectionImpl) o;

        if (id != that.id) return false;
        return address != null ? address.equals(that.address) : that.address == null;
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + id;
        result = 31 * result + (closing.get() ? 1 : 0);
        result = 31 * result + (identitySent.get() ? 1 : 0);
        result = 31 * result + (address != null ? address.hashCode() : 0);
        return result;
    }

    @Override
    public void setMessageReceived(long timeStamp) {
        this.lastMsgReceived = timeStamp;
    }

    @Override
    public long lastMessageReceived() {
        return this.lastMsgReceived;
    }

    @Override
    public void setMessageConsumptionFailed(long timeStamp) {
        this.lastMsgConsumptionFailed = timeStamp;
    }

    @Override
    public long lastMessageConsumptionFailed() {
        return this.lastMsgConsumptionFailed;
    }

    @Override
    public String toString() {
        // JDK8
        return "NSQConnectionImpl [id=" + id + ", identitySent=" + identitySent.get() + ", closing=" + closing + ", address=" + address
                + ", channel=" + channel + ", config=" + config + ", queryTimeoutInMillisecond=" + queryTimeoutInMillisecond
                + "]";
    }


}
