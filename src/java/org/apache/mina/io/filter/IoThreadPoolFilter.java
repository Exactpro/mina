/*
 *   @(#) $Id$
 *
 *   Copyright 2004 The Apache Software Foundation
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.apache.mina.io.filter;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.mina.common.ByteBuffer;
import org.apache.mina.common.IdleStatus;
import org.apache.mina.io.IoHandler;
import org.apache.mina.io.IoHandlerFilter;
import org.apache.mina.io.IoSession;
import org.apache.mina.util.BlockingSet;
import org.apache.mina.util.Queue;
import org.apache.mina.util.Stack;

/**
 * A Thread-pooling filter.  This filter forwards {@link IoHandler} events 
 * to its thread pool.  This is an implementation of
 * <a href="http://deuce.doc.wustl.edu/doc/pspdfs/lf.pdf">Leader/Followers
 * thread pool</a> by Douglas C. Schmidt et al.
 * 
 * @author Trustin Lee (trustin@apache.org)
 * @version $Rev$, $Date$
 */
public class IoThreadPoolFilter implements IoHandlerFilter
{
    /**
     * Default maximum size of thread pool (2G).
     */
    public static final int DEFAULT_MAXIMUM_POOL_SIZE = Integer.MAX_VALUE;

    /**
     * Default keep-alive time of thread pool (1 min).
     */
    public static final int DEFAULT_KEEP_ALIVE_TIME = 60 * 1000;

    private static volatile int threadId = 0;

    private Map buffers = new IdentityHashMap();

    private Stack followers = new Stack();

    private Worker leader;

    private BlockingSet readySessionBuffers = new BlockingSet();

    private Set busySessionBuffers = new HashSet();

    private int maximumPoolSize = DEFAULT_MAXIMUM_POOL_SIZE;

    private int keepAliveTime = DEFAULT_KEEP_ALIVE_TIME;

    private boolean started;

    private boolean shuttingDown;

    private int poolSize;

    private final Object poolSizeLock = new Object();

    /**
     * Creates a new instanceof this filter with default thread pool settings.
     * You'll have to invoke {@link #start()} method to start threads actually.
     */
    public IoThreadPoolFilter()
    {
    }

    /**
     * Returns the number of threads in the thread pool.
     */
    public synchronized int getPoolSize()
    {
        return poolSize;
    }

    /**
     * Returns the maximum size of the thread pool.
     */
    public int getMaximumPoolSize()
    {
        return maximumPoolSize;
    }

    /**
     * Returns the keep-alive time until the thread suicides after it became
     * idle (milliseconds unit).
     */
    public int getKeepAliveTime()
    {
        return keepAliveTime;
    }

    /**
     * Sets the maximum size of the thread pool.
     */
    public void setMaximumPoolSize( int maximumPoolSize )
    {
        if( maximumPoolSize <= 0 )
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
    }

    /**
     * Sets the keep-alive time until the thread suicides after it became idle
     * (milliseconds unit).
     */
    public void setKeepAliveTime( int keepAliveTime )
    {
        this.keepAliveTime = keepAliveTime;
    }

    /**
     * Starts thread pool threads and starts forwarding events to them.
     */
    public synchronized void start()
    {
        if( started )
            return;

        shuttingDown = false;

        leader = new Worker();
        leader.start();
        leader.lead();

        started = true;
    }

    /**
     * Stops all thread pool threads.
     */
    public synchronized void stop()
    {
        if( !started )
            return;

        shuttingDown = true;
        Worker lastLeader = null;
        for( ;; )
        {
            Worker leader = this.leader;
            if( lastLeader == leader )
                break;

            while( leader.isAlive() )
            {
                leader.interrupt();
                try
                {
                    leader.join();
                }
                catch( InterruptedException e )
                {
                }
            }

            lastLeader = leader;
        }

        started = false;
    }

    private void increasePoolSize()
    {
        synchronized( poolSizeLock )
        {
            poolSize++;
        }
    }

    private void decreasePoolSize()
    {
        synchronized( poolSizeLock )
        {
            poolSize--;
        }
    }

    public void sessionOpened( IoHandler nextHandler, IoSession session )
    {
        fireEvent( nextHandler, session, EventType.OPENED, null );
    }

    public void sessionClosed( IoHandler nextHandler, IoSession session )
    {
        fireEvent( nextHandler, session, EventType.CLOSED, null );
    }

    public void sessionIdle( IoHandler nextHandler, IoSession session,
                            IdleStatus status )
    {
        fireEvent( nextHandler, session, EventType.IDLE, status );
    }

    public void exceptionCaught( IoHandler nextHandler, IoSession session,
                                Throwable cause )
    {
        fireEvent( nextHandler, session, EventType.EXCEPTION, cause );
    }

    public void dataRead( IoHandler nextHandler, IoSession session,
                         ByteBuffer buf )
    {
        fireEvent( nextHandler, session, EventType.READ, buf );
    }

    public void dataWritten( IoHandler nextHandler, IoSession session,
                            Object marker )
    {
        fireEvent( nextHandler, session, EventType.WRITTEN, marker );
    }

    private void fireEvent( IoHandler nextHandler, IoSession session,
                           EventType type, Object data )
    {
        SessionBuffer buf = getSessionBuffer( session );
        synchronized( buf )
        {
            buf.nextHandlers.push( nextHandler );
            buf.eventTypes.push( type );
            buf.eventDatum.push( data );
        }

        synchronized( readySessionBuffers )
        {
            if( !busySessionBuffers.contains( buf ) )
            {
                busySessionBuffers.add( buf );
                readySessionBuffers.add( buf );
            }
        }
    }

    private SessionBuffer getSessionBuffer( IoSession session )
    {
        SessionBuffer buf = ( SessionBuffer ) buffers.get( session );
        if( buf == null )
        {
            synchronized( buffers )
            {
                buf = ( SessionBuffer ) buffers.get( session );
                if( buf == null )
                {
                    buf = new SessionBuffer( session );
                    buffers.put( session, buf );
                }
            }
        }
        return buf;
    }

    private void removeSessionBuffer( SessionBuffer buf )
    {
        synchronized( buffers )
        {
            buffers.remove( buf.session );
        }
    }

    private static class SessionBuffer
    {

        private final IoSession session;

        private final Queue nextHandlers = new Queue();

        private final Queue eventTypes = new Queue();

        private final Queue eventDatum = new Queue();

        private SessionBuffer( IoSession session )
        {
            this.session = session;
        }
    }

    private static class EventType
    {
        private static final EventType OPENED = new EventType();

        private static final EventType CLOSED = new EventType();

        private static final EventType READ = new EventType();

        private static final EventType WRITTEN = new EventType();

        private static final EventType IDLE = new EventType();

        private static final EventType EXCEPTION = new EventType();

        private EventType()
        {
        }
    }

    private class Worker extends Thread
    {
        private final Object promotionLock = new Object();

        private Worker()
        {
            super( "IoThreadPool-" + ( threadId++ ) );
            increasePoolSize();
        }

        public void lead()
        {
            synchronized( promotionLock )
            {
                leader = this;
                promotionLock.notify();
            }
        }

        public void run()
        {
            for( ;; )
            {
                if( !waitForPromotion() )
                    break;

                SessionBuffer buf = fetchBuffer();
                giveUpLead();

                if( buf == null )
                {
                    break;
                }

                processEvents( buf );
                follow();
                releaseBuffer( buf );
            }

            decreasePoolSize();
        }

        private SessionBuffer fetchBuffer()
        {
            SessionBuffer buf = null;
            synchronized( readySessionBuffers )
            {
                do
                {
                    buf = null;
                    try
                    {
                        readySessionBuffers.waitForNewItem();
                    }
                    catch( InterruptedException e )
                    {
                        break;
                    }

                    Iterator it = readySessionBuffers.iterator();
                    if( !it.hasNext() )
                    {
                        // exceeded keepAliveTime
                        break;
                    }

                    do
                    {
                        buf = null;
                        buf = ( SessionBuffer ) it.next();
                        it.remove();
                    }
                    while( buf != null && buf.nextHandlers.isEmpty()
                           && it.hasNext() );
                }
                while( buf != null && buf.nextHandlers.isEmpty() );
            }

            return buf;
        }

        private void processEvents( SessionBuffer buf )
        {
            IoSession session = buf.session;
            for( ;; )
            {
                IoHandler nextHandler;
                EventType type;
                Object data;
                synchronized( buf )
                {
                    nextHandler = ( IoHandler ) buf.nextHandlers.pop();
                    if( nextHandler == null )
                        break;

                    type = ( EventType ) buf.eventTypes.pop();
                    data = buf.eventDatum.pop();
                }
                processEvent( nextHandler, session, type, data );
            }
        }

        private void processEvent( IoHandler nextHandler, IoSession session,
                                  EventType type, Object data )
        {
            if( type == EventType.READ )
            {
                nextHandler.dataRead( session, ( ByteBuffer ) data );
            }
            else if( type == EventType.WRITTEN )
            {
                nextHandler.dataWritten( session, data );
            }
            else if( type == EventType.EXCEPTION )
            {
                nextHandler.exceptionCaught( session, ( Throwable ) data );
            }
            else if( type == EventType.IDLE )
            {
                nextHandler.sessionIdle( session, ( IdleStatus ) data );
            }
            else if( type == EventType.OPENED )
            {
                nextHandler.sessionOpened( session );
            }
            else if( type == EventType.CLOSED )
            {
                nextHandler.sessionClosed( session );
            }
        }

        private void follow()
        {
            synchronized( promotionLock )
            {
                if( this != leader )
                {
                    synchronized( followers )
                    {
                        followers.push( this );
                    }
                }
            }
        }

        private void releaseBuffer( SessionBuffer buf )
        {
            synchronized( readySessionBuffers )
            {
                busySessionBuffers.remove( buf );
                if( buf.nextHandlers.isEmpty() )
                {
                    removeSessionBuffer( buf );
                }
                else
                {
                    readySessionBuffers.add( buf );
                }
            }
        }

        private boolean waitForPromotion()
        {
            synchronized( promotionLock )
            {
                if( this != leader )
                {
                    try
                    {
                        int keepAliveTime = getKeepAliveTime();
                        if( keepAliveTime > 0 )
                        {
                            promotionLock.wait( keepAliveTime );
                        }
                        else
                        {
                            promotionLock.wait();
                        }
                    }
                    catch( InterruptedException e )
                    {
                    }
                }

                boolean timeToLead = this == leader;

                if( !timeToLead )
                {
                    // time to die
                    synchronized( followers )
                    {
                        followers.remove( this );
                    }
                }

                return timeToLead;
            }
        }

        private void giveUpLead()
        {
            Worker worker;
            synchronized( followers )
            {
                worker = ( Worker ) followers.pop();
            }

            if( worker != null )
            {
                worker.lead();
            }
            else
            {
                if( !shuttingDown )
                {
                    synchronized( IoThreadPoolFilter.this )
                    {
                        if( !shuttingDown
                            && getPoolSize() < getMaximumPoolSize() )
                        {
                            worker = new Worker();
                            worker.start();
                            worker.lead();
                        }
                    }
                }
            }
        }
    }

    public ByteBuffer filterWrite( IoSession session, ByteBuffer buf )
    {
        return buf;
    }
}