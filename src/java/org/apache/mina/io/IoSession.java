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
package org.apache.mina.io;

import java.net.SocketAddress;

import org.apache.mina.common.ByteBuffer;
import org.apache.mina.common.IdleStatus;
import org.apache.mina.common.SessionConfig;
import org.apache.mina.common.TransportType;

/**
 * A handle which represents connection between two endpoints regardless of 
 * transport types (UDP/IP and TCP/IP).
 * <p>
 * Session provides an attachment per session ({@link #getAttachment()} and
 * {@link #setAttachment(Object)}).  Attachment is an application-specific data
 * which is associated with a session.  It is often an object that represents
 * the state of a higher-level protocol.
 *   
 * @author Trustin Lee (trustin@apache.org)
 * @version $Rev$, $Date$
 */
public interface IoSession
{
    /**
     * Returns the event handler for this session.
     */
    IoHandler getHandler();

    /**
     * Closes this session immediately.
     */
    void close();

    /**
     * Writes the content of the specified <code>buf</code>.
     * This operation is asynchronous, and you'll get notified by
     * {@link IoHandler#dataWritten(IoSession, Object)} event.
     * The specified <code>marker</code> will be passes as a parameter.
     */
    void write( ByteBuffer buf, Object marker );

    /**
     * Returns an attachment of this session.
     */
    Object getAttachment();

    /**
     * Sets an attachment of this session.
     */
    void setAttachment( Object attachment );

    /**
     * Returns transport type of this session.
     */
    TransportType getTransportType();

    /**
     * Returns <code>true</code> if this session is connected with remote peer.
     */
    boolean isConnected();

    /**
     * Returns the configuration of this session.
     */
    SessionConfig getConfig();

    /**
     * Returns the socket address of remote peer. 
     */
    SocketAddress getRemoteAddress();

    /**
     * Returns the socket address of local machine which is associated with this
     * session.
     */
    SocketAddress getLocalAddress();

    /**
     * Returns the total number of bytes which were read from this session.
     */
    long getReadBytes();

    /**
     * Returns the total number of bytes which were written to this session.
     */
    long getWrittenBytes();

    /**
     * Returns the time in millis when I/O occurred lastly.
     */
    long getLastIoTime();

    /**
     * Returns the time in millis when read operation occurred lastly.
     */
    long getLastReadTime();

    /**
     * Returns the time in millis when write operation occurred lastly.
     */
    long getLastWriteTime();

    /**
     * Returns <code>true</code> if this session is idle for the specified 
     * {@link IdleStatus}.
     */
    boolean isIdle( IdleStatus status );
}