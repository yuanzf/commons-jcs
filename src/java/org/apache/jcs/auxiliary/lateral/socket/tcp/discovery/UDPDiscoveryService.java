package org.apache.jcs.auxiliary.lateral.socket.tcp.discovery;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jcs.auxiliary.lateral.LateralCacheAttributes;
import org.apache.jcs.auxiliary.lateral.LateralCacheNoWait;
import org.apache.jcs.auxiliary.lateral.LateralCacheNoWaitFacade;
import org.apache.jcs.engine.behavior.ICompositeCacheManager;
import org.apache.jcs.engine.behavior.ShutdownObservable;
import org.apache.jcs.engine.behavior.ShutdownObserver;

import EDU.oswego.cs.dl.util.concurrent.ClockDaemon;
import EDU.oswego.cs.dl.util.concurrent.ThreadFactory;

/**
 * 
 * This service creates a listener that can create lateral caches and add them
 * to the no wait list.
 * <p>
 * It also creates a sender that periodically broadcasts its availability.
 * <p>
 * The sender also broadcasts a request for other caches to broadcast their
 * addresses.
 * 
 * @author Aaron Smuts
 *  
 */
public class UDPDiscoveryService implements ShutdownObserver
{

    private final static Log log = LogFactory.getLog( UDPDiscoveryService.class );

     //The background broadcaster.
    private static ClockDaemon senderDaemon;

    // thread that listens for messages
    private Thread udpReceiverThread;
    
    // the runanble that the receiver thread runs
    private UDPDiscoveryReceiver receiver;

    private Map facades = new HashMap();

    private LateralCacheAttributes lca = null;

    // the runanble that sends messages via the clock daemon
    private UDPDiscoverySenderThread sender = null;
    
    private String hostAddress = "unknown";
    
    /**
     * 
     * @param facade
     * @param lca
     * @param cacheMgr
     * @param receivingPort
     */
    public UDPDiscoveryService( LateralCacheAttributes lca, ICompositeCacheManager cacheMgr )
    {
        // register for shutdown notification
        ((ShutdownObservable)cacheMgr).registerShutdownObserver( this );

        this.setLca( lca );
        
        try
        {
            // todo, you should be able to set this
            hostAddress = InetAddress.getLocalHost().getHostAddress();
            if ( log.isDebugEnabled() )
            {
                log.debug( "hostAddress = [" + hostAddress + "]" );
            }
        }
        catch ( UnknownHostException e1 )
        {
            log.error( "Couldn't get localhost address", e1 );
        }

        try
        {
            // todo need some kind of recovery here.
            receiver = new UDPDiscoveryReceiver( this, lca.getUdpDiscoveryAddr(), lca.getUdpDiscoveryPort(), cacheMgr );
            udpReceiverThread = new Thread(receiver);
            udpReceiverThread.setDaemon(true);
            //udpReceiverThread.setName( t.getName() + "--UDPReceiver" );
            udpReceiverThread.start();
        }
        catch ( Exception e )
        {
            log.error( "Problem creating UDPDiscoveryReceiver, we won't be able to find any other caches", e );
        }

        // todo only do the passive if receive is inenabled, perhaps set the
        // myhost to null or something on the request
        if ( senderDaemon == null )
        {
            senderDaemon = new ClockDaemon();
            senderDaemon.setThreadFactory( new MyThreadFactory() );
        }
        
        // create a sender thread
        sender = new UDPDiscoverySenderThread( lca.getUdpDiscoveryAddr(), lca
                                      .getUdpDiscoveryPort(), hostAddress, lca.getTcpListenerPort(), this.getCacheNames() );
        
        senderDaemon.executePeriodically( 30 * 1000, sender, false );
    }

    /**
     * Adds a nowait facade under this cachename. If one already existed, it
     * will be overridden.
     * <p>
     * When a broadcast is received from the UDP Discovery receiver, for each
     * cacheName in the message, the add no wait will be called here. To add a
     * no wait, the facade is looked up for this cache name.
     * 
     * @param facade
     * @param cacheName
     * @return true if the facade was not already registered.
     */
    public synchronized boolean addNoWaitFacade( LateralCacheNoWaitFacade facade, String cacheName )
    {
        boolean isNew = !facades.containsKey(cacheName);
        
        // override or put anew, it doesn't matter
        facades.put( cacheName, facade );

        if ( isNew )
        {
            if ( sender != null )
            {
                // need to reset the cache names since we have a new one
                sender.setCacheNames( this.getCacheNames() );                            
            }
        }
        
        return isNew;

    }

    /**
     * This adds nowaits to a facde for the region name. If the region has no
     * facade, then it is not configured to use the lateral cache, and no facde
     * will be created.
     * 
     * @param noWait
     */
    protected void addNoWait( LateralCacheNoWait noWait )
    {
        LateralCacheNoWaitFacade facade = (LateralCacheNoWaitFacade) facades.get( noWait.getCacheName() );
        if ( log.isDebugEnabled() )
        {
            log.debug( "Got facade for " + noWait.getCacheName() + " = " + facade );
        }

        if ( facade != null )
        {
            boolean isNew = facade.addNoWait( noWait );
            if ( log.isDebugEnabled() )
            {
                log.debug( "Called addNoWait, isNew = " + isNew );
            }
        }
        else
        {
            if ( log.isInfoEnabled() )
            {
                log.info( "Different nodes are configured differently.  Region [" + noWait.getCacheName()
                    + "] is not configured to use the lateral cache." );
            }
        }
    }

    /**
     * Send a passive broadcast in response to a request broadcast.  Never send a request for a request.
     * We can respond to our own reques, since a request broadcast is not intended as a connection request.
     * We might want to only send messages, so we would send a request, but never a passive broadcast.
     *
     */
    protected void serviceRequestBroadcast()
    {
        UDPDiscoverySender sender = null;
        try
        {
            // create this connection each time.
            // more robust
            sender = new UDPDiscoverySender( getLca().getUdpDiscoveryAddr(), getLca().getUdpDiscoveryPort() );

            sender.passiveBroadcast( hostAddress, getLca().getTcpListenerPort(), this.getCacheNames() );

            // todo we should consider sending a request broadcast every so
            // often.

            if ( log.isDebugEnabled() )
            {
                log.debug( "Called sender to issue a passive broadcast" );
            }

        }
        catch ( Exception e )
        {
            log.error( "Problem calling the UDP Discovery Sender", e );
        }      
        finally
        {
            try
            {
                if ( sender != null )
                {
                    sender.destroy();                                    
                }
            }
            catch ( Exception e )
            {
                log.error( "Problem closing Passive Broadcast sender, while servicing a request broadcast.", e );
            }
        }        
    }
    
    /**
     * Get all the cache names we have facades for.
     * 
     * @return
     */
    protected ArrayList getCacheNames()
    {
        ArrayList keys = new ArrayList();
        Set keySet = facades.keySet();
        Iterator it = keySet.iterator();
        while ( it.hasNext() )
        {
            String key = (String) it.next();
            keys.add( key );
        }
        return keys;
    }

    /**
     * @param lca The lca to set.
     */
    public void setLca( LateralCacheAttributes lca )
    {
        this.lca = lca;
    }

    /**
     * @return Returns the lca.
     */
    public LateralCacheAttributes getLca()
    {
        return lca;
    }

    /**
     * Allows us to set the daemon status on the clockdaemon
     * 
     * @author aaronsm
     *  
     */
    class MyThreadFactory
        implements ThreadFactory
    {

        /*
         * (non-Javadoc)
         * 
         * @see EDU.oswego.cs.dl.util.concurrent.ThreadFactory#newThread(java.lang.Runnable)
         */
        public Thread newThread( Runnable runner )
        {
            Thread t = new Thread( runner );
            t.setDaemon( true );
            t.setPriority( Thread.MIN_PRIORITY );
            return t;
        }

    }

    /* (non-Javadoc)
     * @see org.apache.jcs.engine.behavior.ShutdownObserver#shutdown()
     */
    public void shutdown()
    {   
        if ( log.isInfoEnabled() )
        {
            log.info( "Shutting down UDP discovery service receiver." );
        }
        
        try
        {
            // no good way to do this right now.
            receiver.shutdown();                        
            udpReceiverThread.interrupt();    
        }
        catch ( Exception e )
        {
            log.error( "Problem interrupting UDP receiver thread." );
        }

        if ( log.isInfoEnabled() )
        {
            log.info( "Shutting down UDP discovery service sender." );
        }
        
        try
        {            
            // interrupt all the threads.
            senderDaemon.shutDown();
        }
        catch ( Exception e )
        {
            log.error( "Problem shutting down UDP sender." );
        }
        
    }
}
