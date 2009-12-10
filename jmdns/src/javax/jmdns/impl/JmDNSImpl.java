///Copyright 2003-2005 Arthur van Hoff, Rick Blair
//Licensed under Apache License version 2.0
//Original license LGPL

package javax.jmdns.impl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import javax.jmdns.ServiceTypeListener;
import javax.jmdns.impl.tasks.Announcer;
import javax.jmdns.impl.tasks.Canceler;
import javax.jmdns.impl.tasks.Prober;
import javax.jmdns.impl.tasks.RecordReaper;
import javax.jmdns.impl.tasks.Renewer;
import javax.jmdns.impl.tasks.Responder;
import javax.jmdns.impl.tasks.ServiceInfoResolver;
import javax.jmdns.impl.tasks.ServiceResolver;
import javax.jmdns.impl.tasks.TypeResolver;

// REMIND: multiple IP addresses

/**
 * mDNS implementation in Java.
 *
 * @version %I%, %G%
 * @author Arthur van Hoff, Rick Blair, Jeff Sonstein, Werner Randelshofer, Pierre Frisch, Scott Lewis
 */
public class JmDNSImpl extends JmDNS
{
    private static Logger logger = Logger.getLogger(JmDNSImpl.class.getName());

    /**
     * This is the multicast group, we are listening to for multicast DNS messages.
     */
    private InetAddress _group;
    /**
     * This is our multicast socket.
     */
    private MulticastSocket _socket;

    /**
     * Used to fix live lock problem on unregester.
     */

    private boolean _closed = false;

    /**
     * Holds instances of JmDNS.DNSListener. Must by a synchronized collection, because it is updated from concurrent
     * threads.
     */
    private List<DNSListener> _listeners;
    /**
     * Holds instances of ServiceListener's. Keys are Strings holding a fully qualified service type. Values are
     * LinkedList's of ServiceListener's.
     */
    private ConcurrentMap<String, List<ServiceListener>> _serviceListeners;

    /**
     * Holds instances of ServiceTypeListener's.
     */
    private List<ServiceTypeListener> _typeListeners;

    /**
     * Cache for DNSEntry's.
     */
    private DNSCache _cache;

    /**
     * This hashtable holds the services that have been registered. Keys are instances of String which hold an all
     * lower-case version of the fully qualified service name. Values are instances of ServiceInfo.
     */
    Map<String, ServiceInfo> _services;

    /**
     * This hashtable holds the service types that have been registered or that have been received in an incoming
     * datagram. Keys are instances of String which hold an all lower-case version of the fully qualified service type.
     * Values hold the fully qualified service type.
     */
    Map<String, String> _serviceTypes;
    /**
     * This is the shutdown hook, we registered with the java runtime.
     */
    protected Thread _shutdown;

    /**
     * Handle on the local host
     */
    private HostInfo _localHost;

    private Thread _incomingListener = null;

    /**
     * Throttle count. This is used to count the overall number of probes sent by JmDNS. When the last throttle
     * increment happened .
     */
    private int _throttle;
    /**
     * Last throttle increment.
     */
    private long _lastThrottleIncrement;

    //
    // 2009-09-16 ldeck: adding docbug patch with slight ammendments
    // 'Fixes two deadlock conditions involving JmDNS.close() - ID: 1473279'
    //
    // ---------------------------------------------------
    /**
     * The timer that triggers our announcements. We can't use the main timer object, because that could cause a
     * deadlock where Prober waits on JmDNS.this lock held by close(), close() waits for us to finish, and we wait for
     * Prober to give us back the timer thread so we can announce. (Patch from docbug in 2006-04-19 still wasn't patched
     * .. so I'm doing it!)
     */
    private Timer _cancelerTimer = new Timer("JmDNS.cancelerTimer");
    // ---------------------------------------------------

    /**
     * The timer is used to dispatch all outgoing messages of JmDNS. It is also used to dispatch maintenance tasks for
     * the DNS cache.
     */
    Timer _timer;

    /**
     * The source for random values. This is used to introduce random delays in responses. This reduces the potential
     * for collisions on the network.
     */
    private final static Random _random = new Random();

    /**
     * This lock is used to coordinate processing of incoming and outgoing messages. This is needed, because the
     * Rendezvous Conformance Test does not forgive race conditions.
     */
    private Object _ioLock = new Object();

    /**
     * If an incoming package which needs an answer is truncated, we store it here. We add more incoming DNSRecords to
     * it, until the JmDNS.Responder timer picks it up. Remind: This does not work well with multiple planned answers
     * for packages that came in from different clients.
     */
    private DNSIncoming _plannedAnswer;

    // State machine
    /**
     * The state of JmDNS.
     * <p/>
     * For proper handling of concurrency, this variable must be changed only using methods advanceState(),
     * revertState() and cancel().
     */
    private DNSState _state = DNSState.PROBING_1;

    /**
     * Timer task associated to the host name. This is used to prevent from having multiple tasks associated to the host
     * name at the same time.
     */
    private TimerTask _task;

    /**
     * This hashtable is used to maintain a list of service types being collected by this JmDNS instance. The key of the
     * hashtable is a service type name, the value is an instance of JmDNS.ServiceCollector.
     *
     * @see #list
     */
    private final Map<String, ServiceCollector> _serviceCollectors = new HashMap<String, ServiceCollector>();

    /**
     * Create an instance of JmDNS.
     *
     * @throws IOException
     */
    public JmDNSImpl() throws IOException
    {
        logger.finer("JmDNS instance created");
        try
        {
            final InetAddress addr = InetAddress.getLocalHost();
            // [PJYF Oct 14 2004] Why do we disallow the loopback address ?
            init(addr.isLoopbackAddress() ? null : addr, addr.getHostName());
        }
        catch (final IOException e)
        {
            init(null, "computer");
        }
    }

    /**
     * Create an instance of JmDNS and bind it to a specific network interface given its IP-address.
     *
     * @param addr
     * @throws IOException
     */
    public JmDNSImpl(InetAddress addr) throws IOException
    {
        try
        {
            init(addr, addr.getHostName());
        }
        catch (final IOException e)
        {
            init(null, "computer");
        }
    }

    /**
     * Initialize everything.
     *
     * @param address
     *            The interface to which JmDNS binds to.
     * @param name
     *            The host name of the interface.
     */
    private void init(InetAddress address, String name) throws IOException
    {
        // A host name with "." is illegal. so strip off everything and append .local.
        String aName = name;
        final int idx = aName.indexOf(".");
        if (idx > 0)
        {
            aName = aName.substring(0, idx);
        }
        aName += ".local.";
        // localHost to IP address binding
        _localHost = new HostInfo(address, aName);

        _cache = new DNSCache(100);

        _listeners = Collections.synchronizedList(new ArrayList<DNSListener>());
        _serviceListeners = new ConcurrentHashMap<String, List<ServiceListener>>();
        _typeListeners = new ArrayList<ServiceTypeListener>();

        _services = new Hashtable<String, ServiceInfo>(20);
        _serviceTypes = new Hashtable<String, String>(20);

        // REMIND: If I could pass in a name for the Timer thread,
        // I would pass' JmDNS.Timer'.
        _timer = new Timer("JmDNS.Timer");
        new RecordReaper(this).start(_timer);

        // (ldeck 2.1.1) preventing shutdown blocking thread
        // -------------------------------------------------
        _shutdown = null;// new Thread(new Shutdown(), "JmDNS.Shutdown");
        // Runtime.getRuntime().addShutdownHook(shutdown);

        _incomingListener = new Thread(new SocketListener(this), "JmDNS.SocketListener");
        _incomingListener.setDaemon(true);
        // -------------------------------------------------

        // Bind to multicast socket
        openMulticastSocket(getLocalHost());
        start(getServices().values());
    }

    private void start(Collection<? extends ServiceInfo> serviceInfos)
    {
        setState(DNSState.PROBING_1);
        _incomingListener.start();
        new Prober(this).start(_timer);
        for (final Iterator<? extends ServiceInfo> iterator = serviceInfos.iterator(); iterator.hasNext();)
        {
            try
            {
                registerService(new ServiceInfoImpl((ServiceInfoImpl) iterator.next()));
            }
            catch (final Exception exception)
            {
                logger.log(Level.WARNING, "start() Registration exception ", exception);
            }
        }
    }

    private void openMulticastSocket(HostInfo hostInfo) throws IOException
    {
        if (_group == null)
        {
            _group = InetAddress.getByName(DNSConstants.MDNS_GROUP);
        }
        if (_socket != null)
        {
            this.closeMulticastSocket();
        }
        _socket = new MulticastSocket(DNSConstants.MDNS_PORT);
        if ((hostInfo != null) && (_localHost.getInterface() != null))
        {
            _socket.setNetworkInterface(hostInfo.getInterface());
        }
        _socket.setTimeToLive(255);
        _socket.joinGroup(_group);
    }

    private void closeMulticastSocket()
    {
        logger.finer("closeMulticastSocket()");
        if (_socket != null)
        {
            // close socket
            try
            {
                _socket.leaveGroup(_group);
                _socket.close();
                if (_incomingListener != null)
                {
                    _incomingListener.join();
                }
            }
            catch (final Exception exception)
            {
                logger.log(Level.WARNING, "closeMulticastSocket() Close socket exception ", exception);
            }
            _socket = null;
        }
    }

    // State machine
    /**
     * Sets the state and notifies all objects that wait on JmDNS.
     */
    public synchronized void advanceState()
    {
        setState(getState().advance());
        notifyAll();
    }

    /**
     * Sets the state and notifies all objects that wait on JmDNS.
     */
    synchronized void revertState()
    {
        setState(getState().revert());
        notifyAll();
    }

    /**
     * Sets the state and notifies all objects that wait on JmDNS.
     */
    synchronized void cancel()
    {
        setState(DNSState.CANCELED);
        notifyAll();
    }

    /**
     * Returns the current state of this info.
     *
     * @return Info state
     */
    public DNSState getState()
    {
        return _state;
    }

    /**
     * Return the DNSCache associated with the cache variable
     *
     * @return DNS cache
     */
    public DNSCache getCache()
    {
        return _cache;
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#getHostName()
     */
    @Override
    public String getHostName()
    {
        return _localHost.getName();
    }

    /**
     * Returns the local host info
     *
     * @return local host info
     */
    public HostInfo getLocalHost()
    {
        return _localHost;
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#getInterface()
     */
    @Override
    public InetAddress getInterface() throws IOException
    {
        return _socket.getInterface();
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#getServiceInfo(java.lang.String, java.lang.String)
     */
    @Override
    public ServiceInfo getServiceInfo(String type, String name)
    {
        return getServiceInfo(type, name, 3 * 1000);
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#getServiceInfo(java.lang.String, java.lang.String, int)
     */
    @Override
    public ServiceInfo getServiceInfo(String type, String name, int timeout)
    {
        final ServiceInfoImpl info = new ServiceInfoImpl(type, name);
        new ServiceInfoResolver(this, info).start(_timer);

        try
        {
            final long end = System.currentTimeMillis() + timeout;
            long delay;
            synchronized (info)
            {
                while (!info.hasData() && (delay = end - System.currentTimeMillis()) > 0)
                {
                    info.wait(delay);
                }
            }
        }
        catch (final InterruptedException e)
        {
            // empty
        }

        return (info.hasData()) ? info : null;
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#requestServiceInfo(java.lang.String, java.lang.String)
     */
    @Override
    public void requestServiceInfo(String type, String name)
    {
        requestServiceInfo(type, name, 3 * 1000);
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#requestServiceInfo(java.lang.String, java.lang.String, int)
     */
    @Override
    public void requestServiceInfo(String type, String name, int timeout)
    {
        registerServiceType(type);
        final ServiceInfoImpl info = new ServiceInfoImpl(type, name);
        new ServiceInfoResolver(this, info).start(_timer);

        try
        {
            final long end = System.currentTimeMillis() + timeout;
            long delay;
            synchronized (info)
            {
                while (!info.hasData() && (delay = end - System.currentTimeMillis()) > 0)
                {
                    info.wait(delay);
                }
            }
        }
        catch (final InterruptedException e)
        {
            // empty
        }
    }

    void handleServiceResolved(ServiceInfoImpl info)
    {
        List<ServiceListener> list = _serviceListeners.get(info._type.toLowerCase());
        List<ServiceListener> listCopy = Collections.emptyList();
        if ((list != null) && (!list.isEmpty()))
        {
            synchronized (list)
            {
                listCopy = new ArrayList<ServiceListener>(list);
            }
            final ServiceEvent event = new ServiceEventImpl(this, info._type, info.getName(), info);
            for (ServiceListener listener : listCopy)
            {
                listener.serviceResolved(event);
            }
        }
    }

    /**
     * @see javax.jmdns.JmDNS#addServiceTypeListener(javax.jmdns.ServiceTypeListener )
     */
    @Override
    public void addServiceTypeListener(ServiceTypeListener listener) throws IOException
    {
        synchronized (this)
        {
            _typeListeners.remove(listener);
            _typeListeners.add(listener);
        }

        // report cached service types
        for (final Iterator<String> iterator = _serviceTypes.values().iterator(); iterator.hasNext();)
        {
            listener.serviceTypeAdded(new ServiceEventImpl(this, iterator.next(), null, null));
        }

        new TypeResolver(this).start(_timer);
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#removeServiceTypeListener(javax.jmdns.ServiceTypeListener)
     */
    @Override
    public void removeServiceTypeListener(ServiceTypeListener listener)
    {
        synchronized (this)
        {
            _typeListeners.remove(listener);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#addServiceListener(java.lang.String, javax.jmdns.ServiceListener)
     */
    @Override
    public void addServiceListener(String type, ServiceListener listener)
    {
        final String lotype = type.toLowerCase();
        removeServiceListener(lotype, listener);
        List<ServiceListener> list = _serviceListeners.get(lotype);
        if (list == null)
        {
            _serviceListeners.putIfAbsent(lotype, new LinkedList<ServiceListener>());
            list = _serviceListeners.get(lotype);
        }
        synchronized (list)
        {
            if (!list.contains(listener))
            {
                list.add(listener);
            }
        }

        // report cached service types
        final List<ServiceEvent> serviceEvents = new ArrayList<ServiceEvent>();
        synchronized (_cache)
        {
            Collection<DNSEntry> dnsEntryLits = this.getCache().allValues();
            for (DNSEntry entry : dnsEntryLits)
            {
                final DNSRecord record = (DNSRecord) entry;
                if (DNSRecordType.TYPE_SRV.equals(record.getRecordType()))
                {
                    if (record.getName().endsWith(type))
                    {
                        serviceEvents.add(new ServiceEventImpl(this, type, toUnqualifiedName(type, record.getName()),
                                null));
                    }
                }
            }
        }
        // Actually call listener with all service events added above
        for (ServiceEvent serviceEvent : serviceEvents)
        {
            listener.serviceAdded(serviceEvent);
        }
        // Create/start ServiceResolver
        new ServiceResolver(this, type).start(_timer);
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#removeServiceListener(java.lang.String, javax.jmdns.ServiceListener)
     */
    @Override
    public void removeServiceListener(String type, ServiceListener listener)
    {
        String aType = type.toLowerCase();
        List<ServiceListener> list = _serviceListeners.get(aType);
        if (list != null)
        {
            synchronized (list)
            {
                list.remove(listener);
                if (list.isEmpty())
                {
                    _serviceListeners.remove(aType, list);
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#registerService(javax.jmdns.ServiceInfo)
     */
    @Override
    public void registerService(ServiceInfo infoAbstract) throws IOException
    {
        final ServiceInfoImpl info = (ServiceInfoImpl) infoAbstract;

        registerServiceType(info._type);

        // bind the service to this address
        info._server = _localHost.getName();
        info._addr = _localHost.getAddress();

        synchronized (this)
        {
            makeServiceNameUnique(info);
            _services.put(info.getQualifiedName().toLowerCase(), info);
        }

        new /* Service */Prober(this).start(_timer);
        try
        {
            synchronized (info)
            {
                while (info.getState().compareTo(DNSState.ANNOUNCED) < 0)
                {
                    info.wait();
                }
            }
        }
        catch (final InterruptedException e)
        {
            // empty
        }
        logger.fine("registerService() JmDNS registered service as " + info);
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#unregisterService(javax.jmdns.ServiceInfo)
     */
    @Override
    public void unregisterService(ServiceInfo infoAbstract)
    {
        final ServiceInfoImpl info = (ServiceInfoImpl) infoAbstract;
        synchronized (this)
        {
            _services.remove(info.getQualifiedName().toLowerCase());
        }
        info.cancel();

        // Note: We use this lock object to synchronize on it.
        // Synchronizing on another object (e.g. the ServiceInfo) does
        // not make sense, because the sole purpose of the lock is to
        // wait until the canceler has finished. If we synchronized on
        // the ServiceInfo or on the Canceler, we would block all
        // accesses to synchronized methods on that object. This is not
        // what we want!
        final Object lock = new Object();
        new Canceler(this, info, lock).start(_timer);

        // Remind: We get a deadlock here, if the Canceler does not run!
        try
        {
            synchronized (lock)
            {
                lock.wait();
            }
        }
        catch (final InterruptedException e)
        {
            // empty
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.jmdns.JmDNS#unregisterAllServices()
     */
    @Override
    public void unregisterAllServices()
    {
        logger.finer("unregisterAllServices()");
        if (_services.size() == 0)
        {
            return;
        }

        Collection<? extends ServiceInfo> list;
        synchronized (this)
        {
            list = new LinkedList<ServiceInfo>(_services.values());
            _services.clear();
        }
        for (final Iterator<? extends ServiceInfo> iterator = list.iterator(); iterator.hasNext();)
        {
            ((ServiceInfoImpl) iterator.next()).cancel();
        }

        final Object lock = new Object();
        //
        // 2009-09-16 ldeck: adding docbug patch with slight ammendments
        // 'Fixes two deadlock conditions involving JmDNS.close() - ID: 1473279'
        //
        // ---------------------------------------------------
        new Canceler(this, list, lock).start(_cancelerTimer);
        // new Canceler(this, list, lock).start(timer);
        // ---------------------------------------------------
        // Remind: We get a livelock here, if the Canceler does not run!
        try
        {
            synchronized (lock)
            {
                if (!_closed)
                {
                    lock.wait();
                }
            }
        }
        catch (final InterruptedException e)
        {
            // empty
        }

    }

    /**
     * @see javax.jmdns.JmDNS#registerServiceType(java.lang.String)
     */
    @Override
    public void registerServiceType(String type)
    {
        final String name = type.toLowerCase();
        if (_serviceTypes.get(name) == null)
        {
            if ((type.indexOf("._mdns._udp.") < 0) && !type.endsWith(".in-addr.arpa."))
            {
                Collection<ServiceTypeListener> list;
                synchronized (this)
                {
                    _serviceTypes.put(name, type);
                    list = new LinkedList<ServiceTypeListener>(_typeListeners);
                }
                for (ServiceTypeListener listener : list)
                {
                    listener.serviceTypeAdded(new ServiceEventImpl(this, type, null, null));
                }
            }
        }
    }

    /**
     * Generate a possibly unique name for a host using the information we have in the cache.
     *
     * @return returns true, if the name of the host had to be changed.
     */
    // private boolean makeHostNameUnique(DNSRecord.Address host)
    // {
    // final String originalName = host.getName();
    // System.currentTimeMillis();
    //
    // boolean collision;
    // do
    // {
    // collision = false;
    //
    // // Check for collision in cache
    // for (DNSCache.CacheNode j = _cache.find(host.getName().toLowerCase()); j != null; j = j.next())
    // {
    // if (false)
    // {
    // host._name = incrementName(host.getName());
    // collision = true;
    // break;
    // }
    // }
    // }
    // while (collision);
    //
    // if (originalName.equals(host.getName()))
    // {
    // return false;
    // }
    // return true;
    // }

    /**
     * Generate a possibly unique name for a service using the information we have in the cache.
     *
     * @return returns true, if the name of the service info had to be changed.
     */
    private boolean makeServiceNameUnique(ServiceInfoImpl info)
    {
        final String originalQualifiedName = info.getQualifiedName();
        final long now = System.currentTimeMillis();

        boolean collision;
        do
        {
            collision = false;

            // Check for collision in cache
            Collection<? extends DNSEntry> entryList = this.getCache().getDNSEntryList(
                    info.getQualifiedName().toLowerCase());
            if (entryList != null)
            {
                for (DNSEntry dnsEntry : entryList)
                {
                    if (DNSRecordType.TYPE_SRV.equals(dnsEntry.getRecordType()) && !dnsEntry.isExpired(now))
                    {
                        final DNSRecord.Service s = (DNSRecord.Service) dnsEntry;
                        if (s._port != info._port || !s._server.equals(_localHost.getName()))
                        {
                            logger.finer("makeServiceNameUnique() JmDNS.makeServiceNameUnique srv collision:"
                                    + dnsEntry + " s.server=" + s._server + " " + _localHost.getName() + " equals:"
                                    + (s._server.equals(_localHost.getName())));
                            info.setName(incrementName(info.getName()));
                            collision = true;
                            break;
                        }
                    }
                }
            }

            // Check for collision with other service infos published by JmDNS
            final Object selfService = _services.get(info.getQualifiedName().toLowerCase());
            if (selfService != null && selfService != info)
            {
                info.setName(incrementName(info.getName()));
                collision = true;
            }
        }
        while (collision);

        return !(originalQualifiedName.equals(info.getQualifiedName()));
    }

    String incrementName(String name)
    {
        String aName = name;
        try
        {
            final int l = aName.lastIndexOf('(');
            final int r = aName.lastIndexOf(')');
            if ((l >= 0) && (l < r))
            {
                aName = aName.substring(0, l) + "(" + (Integer.parseInt(aName.substring(l + 1, r)) + 1) + ")";
            }
            else
            {
                aName += " (2)";
            }
        }
        catch (final NumberFormatException e)
        {
            aName += " (2)";
        }
        return aName;
    }

    /**
     * Add a listener for a question. The listener will receive updates of answers to the question as they arrive, or
     * from the cache if they are already available.
     *
     * @param listener
     *            DSN listener
     * @param question
     *            DNS query
     */
    public void addListener(DNSListener listener, DNSQuestion question)
    {
        final long now = System.currentTimeMillis();

        // add the new listener
        synchronized (this)
        {
            _listeners.add(listener);
        }

        // report existing matched records

        if (question != null)
        {
            Collection<? extends DNSEntry> entryList = this.getCache()
                    .getDNSEntryList(question.getName().toLowerCase());
            if (entryList != null)
            {
                for (DNSEntry dnsEntry : entryList)
                {
                    if (question.answeredBy(dnsEntry) && !dnsEntry.isExpired(now))
                    {
                        listener.updateRecord(this.getCache(), now, dnsEntry);
                    }
                }
            }
        }
    }

    /**
     * Remove a listener from all outstanding questions. The listener will no longer receive any updates.
     *
     * @param listener
     *            DSN listener
     */
    public void removeListener(DNSListener listener)
    {
        synchronized (this)
        {
            _listeners.remove(listener);
        }
    }

    // Remind: Method updateRecord should receive a better name.
    /**
     * Notify all listeners that a record was updated.
     *
     * @param now
     *            update date
     * @param rec
     *            DNS record
     */
    public void updateRecord(long now, DNSRecord rec)
    {
        // We do not want to block the entire DNS while we are updating the
        // record for each listener (service info)
        List<DNSListener> listenerList = null;
        synchronized (this)
        {
            listenerList = new ArrayList<DNSListener>(_listeners);
        }
        for (DNSListener listener : listenerList)
        {
            listener.updateRecord(this.getCache(), now, rec);
        }
        if (DNSRecordType.TYPE_PTR.equals(rec.getRecordType()) || DNSRecordType.TYPE_SRV.equals(rec.getRecordType()))
        {
            List<ServiceListener> list = _serviceListeners.get(rec._name.toLowerCase());
            List<ServiceListener> serviceListenerList = Collections.emptyList();
            // Iterate on a copy in case listeners will modify it
            if (list != null)
            {
                synchronized (list)
                {
                    serviceListenerList = new ArrayList<ServiceListener>(list);
                }
            }
            if (serviceListenerList != null)
            {
                final boolean expired = rec.isExpired(now);
                final String type = rec.getName();
                final String name = ((DNSRecord.Pointer) rec).getAlias();
                // DNSRecord old = (DNSRecord)services.get(name.toLowerCase());
                if (!expired)
                {
                    // new record
                    final ServiceEvent event = new ServiceEventImpl(this, type, toUnqualifiedName(type, name), null);
                    for (ServiceListener listener : serviceListenerList)
                    {
                        listener.serviceAdded(event);
                    }
                }
                else
                {
                    // expire record
                    final ServiceEvent event = new ServiceEventImpl(this, type, toUnqualifiedName(type, name), null);
                    for (ServiceListener listener : serviceListenerList)
                    {
                        listener.serviceRemoved(event);
                    }
                }
            }
        }
    }

    /**
     * Handle an incoming response. Cache answers, and pass them on to the appropriate questions.
     *
     * @throws IOException
     */
    void handleResponse(DNSIncoming msg) throws IOException
    {
        final long now = System.currentTimeMillis();

        boolean hostConflictDetected = false;
        boolean serviceConflictDetected = false;

        for (DNSRecord rec : msg._answers)
        {
            boolean isInformative = false;
            final boolean expired = rec.isExpired(now);

            // update the cache
            final DNSRecord c = (DNSRecord) _cache.get(rec);
            if (c != null)
            {
                if (expired)
                {
                    isInformative = true;
                    _cache.remove(c);
                }
                else
                {
                    c.resetTTL(rec);
                    rec = c;
                }
            }
            else
            {
                if (!expired)
                {
                    isInformative = true;
                    _cache.addDNSEntry(rec);
                }
            }
            switch (rec.getRecordType())
            {
                case TYPE_PTR:
                    // handle _mdns._udp records
                    if (rec.getName().indexOf("._mdns._udp.") >= 0)
                    {
                        if (!expired && rec._name.startsWith("_services._mdns._udp."))
                        {
                            isInformative = true;
                            registerServiceType(((DNSRecord.Pointer) rec)._alias);
                        }
                        continue;
                    }
                    registerServiceType(rec._name);
                    break;
                default:
                    break;
            }

            if (DNSRecordType.TYPE_A.equals(rec.getRecordType()) || DNSRecordType.TYPE_AAAA.equals(rec.getRecordType()))
            {
                hostConflictDetected |= rec.handleResponse(this);
            }
            else
            {
                serviceConflictDetected |= rec.handleResponse(this);
            }

            // notify the listeners
            if (isInformative)
            {
                updateRecord(now, rec);
            }
        }

        if (hostConflictDetected || serviceConflictDetected)
        {
            new Prober(this).start(_timer);
        }
    }

    /**
     * Handle an incoming query. See if we can answer any part of it given our service infos.
     *
     * @param in
     * @param addr
     * @param port
     * @throws IOException
     */
    void handleQuery(DNSIncoming in, InetAddress addr, int port) throws IOException
    {
        // Track known answers
        boolean hostConflictDetected = false;
        boolean serviceConflictDetected = false;
        final long expirationTime = System.currentTimeMillis() + DNSConstants.KNOWN_ANSWER_TTL;
        for (DNSRecord answer : in._answers)
        {
            if (DNSRecordType.TYPE_A.equals(answer.getRecordType())
                    || DNSRecordType.TYPE_AAAA.equals(answer.getRecordType()))
            {
                hostConflictDetected |= answer.handleQuery(this, expirationTime);
            }
            else
            {
                serviceConflictDetected |= answer.handleQuery(this, expirationTime);
            }
        }

        if (_plannedAnswer != null)
        {
            _plannedAnswer.append(in);
        }
        else
        {
            if (in.isTruncated())
            {
                _plannedAnswer = in;
            }

            new Responder(this, in, addr, port).start();
        }

        if (hostConflictDetected || serviceConflictDetected)
        {
            new Prober(this).start(_timer);
        }
    }

    /**
     * Add an answer to a question. Deal with the case when the outgoing packet overflows
     *
     * @param in
     * @param addr
     * @param port
     * @param out
     * @param rec
     * @return outgoing answer
     * @throws IOException
     */
    public DNSOutgoing addAnswer(DNSIncoming in, InetAddress addr, int port, DNSOutgoing out, DNSRecord rec)
            throws IOException
    {
        DNSOutgoing newOut = out;
        if (newOut == null)
        {
            newOut = new DNSOutgoing(DNSConstants.FLAGS_QR_RESPONSE | DNSConstants.FLAGS_AA);
        }
        try
        {
            newOut.addAnswer(in, rec);
        }
        catch (final IOException e)
        {
            newOut.setFlags(newOut.getFlags() | DNSConstants.FLAGS_TC);
            newOut.setId(in.getId());
            newOut.finish();
            send(newOut);

            newOut = new DNSOutgoing(DNSConstants.FLAGS_QR_RESPONSE | DNSConstants.FLAGS_AA);
            newOut.addAnswer(in, rec);
        }
        return newOut;
    }

    /**
     * Send an outgoing multicast DNS message.
     *
     * @param out
     * @throws IOException
     */
    public void send(DNSOutgoing out) throws IOException
    {
        out.finish();
        if (!out.isEmpty())
        {
            final DatagramPacket packet = new DatagramPacket(out._data, out._off, _group, DNSConstants.MDNS_PORT);

            try
            {
                final DNSIncoming msg = new DNSIncoming(packet);
                logger.finest("send() JmDNS out:" + msg.print(true));
            }
            catch (final IOException e)
            {
                logger.throwing(getClass().toString(), "send(DNSOutgoing) - JmDNS can not parse what it sends!!!", e);
            }
            final MulticastSocket ms = _socket;
            if (ms != null && !ms.isClosed())
                ms.send(packet);
        }
    }

    public void startAnnouncer()
    {
        new Announcer(this).start(_timer);
    }

    public void startRenewer()
    {
        new Renewer(this).start(_timer);
    }

    public void schedule(TimerTask task, int delay)
    {
        _timer.schedule(task, delay);
    }

    // REMIND: Why is this not an anonymous inner class?
    /**
     * Shutdown operations.
     */
    protected class Shutdown implements Runnable
    {
        public void run()
        {
            _shutdown = null;
            close();
        }
    }

    /**
     * Recover jmdns when there is an error.
     */
    public void recover()
    {
        logger.finer("recover()");
        // We have an IO error so lets try to recover if anything happens lets
        // close it.
        // This should cover the case of the IP address changing under our feet
        if (DNSState.CANCELED != getState())
        {
            synchronized (this)
            { // Synchronize only if we are not already in process to prevent
                // dead locks
                //
                logger.finer("recover() Cleanning up");
                // Stop JmDNS
                setState(DNSState.CANCELED); // This protects against recursive
                // calls

                // We need to keep a copy for reregistration
                final Collection<ServiceInfo> oldServiceInfos = new ArrayList<ServiceInfo>(getServices().values());

                // Cancel all services
                unregisterAllServices();
                disposeServiceCollectors();
                //
                // close multicast socket
                closeMulticastSocket();
                //
                _cache.clear();
                logger.finer("recover() All is clean");
                //
                // All is clear now start the services
                //
                try
                {
                    openMulticastSocket(getLocalHost());
                    start(oldServiceInfos);
                }
                catch (final Exception exception)
                {
                    logger.log(Level.WARNING, "recover() Start services exception ", exception);
                }
                logger.log(Level.WARNING, "recover() We are back!");
            }
        }
    }

    /**
     * @see javax.jmdns.JmDNS#close()
     */
    @Override
    public void close()
    {
        if (getState() != DNSState.CANCELED)
        {
            synchronized (this)
            { // Synchronize only if we are not already in process to prevent
                // dead locks
                // Stop JmDNS
                setState(DNSState.CANCELED); // This protects against recursive
                // calls

                unregisterAllServices();
                disposeServiceCollectors();

                // close socket
                closeMulticastSocket();

                // Stop the timer
                _timer.cancel();

                // remove the shutdown hook
                if (_shutdown != null)
                {
                    Runtime.getRuntime().removeShutdownHook(_shutdown);
                }

            }
        }
    }

    /**
     * List cache entries, for debugging only.
     */
    void print()
    {
        System.out.println(_cache.toString());
        System.out.println();
    }

    /**
     * @see javax.jmdns.JmDNS#printServices()
     */
    @Override
    public void printServices()
    {
        System.err.println(toString());
    }

    @Override
    public String toString()
    {
        final StringBuffer aLog = new StringBuffer();
        aLog.append("\t---- Services -----");
        if (_services != null)
        {
            for (String key : _services.keySet())
            {
                aLog.append("\n\t\tService: " + key + ": " + _services.get(key));
            }
        }
        aLog.append("\n");
        aLog.append("\t---- Types ----");
        if (_serviceTypes != null)
        {
            for (String key : _serviceTypes.keySet())
            {
                aLog.append("\n\t\tType: " + key + ": " + _serviceTypes.get(key));
            }
        }
        aLog.append("\n");
        aLog.append(_cache.toString());
        aLog.append("\n");
        aLog.append("\t---- Service Collectors ----");
        if (_serviceCollectors != null)
        {
            synchronized (_serviceCollectors)
            {
                for (String key : _serviceCollectors.keySet())
                {
                    aLog.append("\n\t\tService Collector: " + key + ": " + _serviceCollectors.get(key));
                }
                _serviceCollectors.clear();
            }
        }
        return aLog.toString();
    }

    /**
     * @see javax.jmdns.JmDNS#list(java.lang.String)
     */
    @Override
    public ServiceInfo[] list(String type)
    {
        // Implementation note: The first time a list for a given type is
        // requested, a ServiceCollector is created which collects service
        // infos. This greatly speeds up the performance of subsequent calls
        // to this method. The caveats are, that 1) the first call to this
        // method
        // for a given type is slow, and 2) we spawn a ServiceCollector
        // instance for each service type which increases network traffic a
        // little.

        ServiceCollector collector;

        boolean newCollectorCreated;
        //
        // 2009-09-16 ldeck: adding docbug patch with slight ammendments
        // 'Fixes two deadlock conditions involving JmDNS.close() - ID: 1473279'
        //
        synchronized (this) // to avoid possible deadlock with a close() in another thread
        {
            // If we've been cancelled but got the lock, we're about to die anyway,
            // so just return an empty array.
            if (_state == DNSState.CANCELED)
            {
                return new ServiceInfo[0];
            }

            synchronized (_serviceCollectors)
            {
                collector = _serviceCollectors.get(type);
                if (collector == null)
                {
                    collector = new ServiceCollector(type);
                    _serviceCollectors.put(type, collector);
                    addServiceListener(type, collector);
                    newCollectorCreated = true;
                }
                else
                {
                    newCollectorCreated = false;
                }
            }
        }

        // After creating a new ServiceCollector, we collect service infos for
        // 200 milliseconds. This should be enough time, to get some service
        // infos from the network.
        if (newCollectorCreated)
        {
            try
            {
                Thread.sleep(200);
            }
            catch (final InterruptedException e)
            {
                /* Stub */
            }
        }

        return collector.list();
    }

    /**
     * This method disposes all ServiceCollector instances which have been created by calls to method
     * <code>list(type)</code>.
     *
     * @see #list
     */
    private void disposeServiceCollectors()
    {
        logger.finer("disposeServiceCollectors()");
        synchronized (_serviceCollectors)
        {
            for (ServiceCollector collector : _serviceCollectors.values())
            {
                removeServiceListener(collector._type, collector);
            }
            _serviceCollectors.clear();
        }
    }

    /**
     * Instances of ServiceCollector are used internally to speed up the performance of method <code>list(type)</code>.
     *
     * @see #list
     */
    private static class ServiceCollector implements ServiceListener
    {
        // private static Logger logger = Logger.getLogger(ServiceCollector.class.getName());

        /**
         * A set of collected service instance names.
         */
        private final Map<String, ServiceInfo> _infos = Collections.synchronizedMap(new HashMap<String, ServiceInfo>());

        public String _type;

        public ServiceCollector(String type)
        {
            this._type = type;
        }

        /**
         * A service has been added.
         *
         * @param event
         *            service event
         */
        public void serviceAdded(ServiceEvent event)
        {
            synchronized (_infos)
            {
                event.getDNS().requestServiceInfo(event.getType(), event.getName(), 0);
            }
        }

        /**
         * A service has been removed.
         *
         * @param event
         *            service event
         */
        public void serviceRemoved(ServiceEvent event)
        {
            synchronized (_infos)
            {
                _infos.remove(event.getName());
            }
        }

        /**
         * A service has been resolved. Its details are now available in the ServiceInfo record.
         *
         * @param event
         *            service event
         */
        public void serviceResolved(ServiceEvent event)
        {
            synchronized (_infos)
            {
                _infos.put(event.getName(), event.getInfo());
            }
        }

        /**
         * Returns an array of all service infos which have been collected by this ServiceCollector.
         *
         * @return Service Info array
         */
        public ServiceInfoImpl[] list()
        {
            synchronized (_infos)
            {
                return _infos.values().toArray(new ServiceInfoImpl[_infos.size()]);
            }
        }

        @Override
        public String toString()
        {
            final StringBuffer aLog = new StringBuffer();
            synchronized (_infos)
            {
                for (String key : _infos.keySet())
                {
                    aLog.append("\n\t\tService: " + key + ": " + _infos.get(key));
                }
            }
            return aLog.toString();
        }
    }

    private static String toUnqualifiedName(String type, String qualifiedName)
    {
        if (qualifiedName.endsWith(type))
        {
            return qualifiedName.substring(0, qualifiedName.length() - type.length() - 1);
        }
        return qualifiedName;
    }

    public void setState(DNSState state)
    {
        this._state = state;
    }

    public void setTask(TimerTask task)
    {
        this._task = task;
    }

    public TimerTask getTask()
    {
        return _task;
    }

    public Map<String, ServiceInfo> getServices()
    {
        return _services;
    }

    public void setLastThrottleIncrement(long lastThrottleIncrement)
    {
        this._lastThrottleIncrement = lastThrottleIncrement;
    }

    public long getLastThrottleIncrement()
    {
        return _lastThrottleIncrement;
    }

    public void setThrottle(int throttle)
    {
        this._throttle = throttle;
    }

    public int getThrottle()
    {
        return _throttle;
    }

    public static Random getRandom()
    {
        return _random;
    }

    public void setIoLock(Object ioLock)
    {
        this._ioLock = ioLock;
    }

    public Object getIoLock()
    {
        return _ioLock;
    }

    public void setPlannedAnswer(DNSIncoming plannedAnswer)
    {
        this._plannedAnswer = plannedAnswer;
    }

    public DNSIncoming getPlannedAnswer()
    {
        return _plannedAnswer;
    }

    void setLocalHost(HostInfo localHost)
    {
        this._localHost = localHost;
    }

    public Map<String, String> getServiceTypes()
    {
        return _serviceTypes;
    }

    public void setClosed(boolean closed)
    {
        this._closed = closed;
    }

    public boolean isClosed()
    {
        return _closed;
    }

    public MulticastSocket getSocket()
    {
        return _socket;
    }

    public InetAddress getGroup()
    {
        return _group;
    }
}
