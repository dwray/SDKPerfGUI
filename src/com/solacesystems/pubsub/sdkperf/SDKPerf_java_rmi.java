/** 
*  Copyright 2009-2023 Solace Corporation. All rights reserved.
*  
*  http://www.solacesystems.com
*  
*  This source is distributed WITHOUT ANY WARRANTY or support;
*  without even the implied warranty of MERCHANTABILITY or FITNESS FOR
*  A PARTICULAR PURPOSE.  All parts of this program are subject to
*  change without notice including the program's CLI options.
*
*  Unlimited use and re-distribution of this unmodified source code is   
*  authorized only with written permission.  Use of part or modified  
*  source code must carry prominent notices stating that you modified it, 
*  and give a relevant date.
*/
package com.solacesystems.pubsub.sdkperf;


import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Vector;

import javax.transaction.xa.XAResource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.solacesystems.common.util.CPUUsage;
import com.solacesystems.common.util.InvalidStateException;
import com.solacesystems.pubsub.sdkperf.config.CliPropertiesParser;
import com.solacesystems.pubsub.sdkperf.config.EpConfigProperties;
import com.solacesystems.pubsub.sdkperf.config.RuntimeProperties;
import com.solacesystems.pubsub.sdkperf.core.AbstractCacheLiveDataAction;
import com.solacesystems.pubsub.sdkperf.core.AbstractClientCollection;
import com.solacesystems.pubsub.sdkperf.core.ClientFactory;
import com.solacesystems.pubsub.sdkperf.core.Constants.EndpointAccessType;
import com.solacesystems.pubsub.sdkperf.core.Constants.GenericAuthenticationScheme;
import com.solacesystems.pubsub.sdkperf.core.Constants.ToolMode;
import com.solacesystems.pubsub.sdkperf.core.GenericStatType;
import com.solacesystems.pubsub.sdkperf.core.PubSubException;
import com.solacesystems.pubsub.sdkperf.core.RtrperfClientCollection;
import com.solacesystems.pubsub.sdkperf.core.SdkperfClientCollection;
import com.solacesystems.pubsub.sdkperf.core.StatFormatHelper;
import com.solacesystems.pubsub.sdkperf.util.DataTypes.SubscriberDestinationsType;
import com.solacesystems.pubsub.sdkperf.util.MemoryUtils;
import com.solacesystems.pubsub.sdkperf.util.RateDataPoint;
import com.solacesystems.pubsub.sdkperf.util.Timing;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Main for sdkperf command line performance test tool.
 * 
 * Through its various sdkperf cli options the tool supports
 * a variety of performance related testing.  To understand each
 * cli options, see the tool cli help output for each option.
 * Some examples of types of tests that can be accomplished are:
 *      - Throughput testing
 *      - Latency testing
 *      - Message order checking
 *      - Message CRC checking
 *      - Cache requesting
 * The code flow of the program can be described as follows:
 *      - Parse cli options
 *      - Initialize the api
 *      - Create the clients
 *      - Connect the clients
 *      - Add any client subscriptions / bind destinations.
 *      - Start any publishing or cache requesting
 *      - Wait for publishing / cache requesting to be done
 *      - Cleanup subscriptions
 *      - Disconnect clients
 *      - Display stats collected during the test run.
 */
public class SDKPerf_java_rmi implements RemoteSDKP {
	private static int port;

	@Override
	public void doRemoteShutdown() throws RemoteException {
		// end asynchronously to avoid the remote GUI freezing
		Thread thread = new Thread() {
			public void run() {
				System.exit(0);
			}
		};
		thread.start();
	}
	long totalPub;
	long totalSub;
	@Override
	public long getPubCount() {
		try {
			totalPub = _clientCollection.getSdkStat(GenericStatType.TOTAL_MSGS_SENT,SdkperfClientCollection.ALL_CLIENT_INDEX);
			return totalPub;
		} catch (PubSubException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return totalPub;
	}
	@Override
	public long getSubCount() {
		try {
			totalSub = _clientCollection.getSdkStat(GenericStatType.TOTAL_MSGS_RECVED,SdkperfClientCollection.ALL_CLIENT_INDEX);
			return totalSub;

		} catch (PubSubException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return totalSub;
	}

	private static final Log Trace = LogFactory.getLog(SDKPerf_java_rmi.class);
	private CPUUsage _cpuUsage = null;
	private AbstractClientCollection _clientCollection = null;
	private List<String> _clientTransactedSessionNames = new Vector<String>();
	private List<String> _clientXids = new Vector<String>();
	
	private RuntimeProperties _props = null;
	
	private LinkedList<RateDataPoint> pubRatePoints = new LinkedList<RateDataPoint>();
	private LinkedList<RateDataPoint> subRatePoints = new LinkedList<RateDataPoint>();
	
	private static boolean fromMain = false;
	
	public SDKPerf_java_rmi(RuntimeProperties props) {
		_props = props;
	}
	
	public SDKPerf_java_rmi(RuntimeProperties props, AbstractClientCollection acc) {
		_props = props;
		_clientCollection = acc;
	}

	// This variable can be used to know if we are running within an application server.
	public static boolean mainFunctionCalled = false;
	
	public static void main(String[] args) throws Exception {
		
                // This variable can be used to know if we are running within an application server.
		fromMain = true;
		
		RuntimeProperties props = generateRuntimeProperties(args);
		
		// Check if a configuration error has occurred
		if (props == null)
			System.exit(1);

		SDKPerf_java_rmi sdkperfJava = new SDKPerf_java_rmi(props);
		// check if RMI settings requested
		port = Integer.parseInt(System.getProperty("com.sun.management.jmxremote.port", "-1"));
		if (port != -1) {
			// set up RMI
			String ClientName = System.getProperty("com.solace.clientname");
			int RegistryPort = Integer.parseInt(System.getProperty("com.sun.management.jmxremote.registry.port"));
			try {
				// Bind the remote object's stub in the registry

				RemoteSDKP stub = (RemoteSDKP) UnicastRemoteObject.exportObject(sdkperfJava, 0);
				System.out.println("Registry Port "+RegistryPort);

				Registry registry = LocateRegistry.getRegistry(RegistryPort);
				// this is going to get bound and unbound repeatedly
				registry.rebind("RemoteSDKP"+ClientName, stub);

				System.err.println("Server ready");
			} catch (Exception e) {
				System.err.println("Server exception: " + e.toString());
				e.printStackTrace();
			}
		}

		if (sdkperfJava.run(true) == ReturnCode.FAILURE) {
			System.exit(1);
		} else {
			System.exit(0);
		}

	}

	public static RuntimeProperties generateRuntimeProperties(String[] args) {
		return CliPropertiesParser.parseCli(args, System.err);
	}
	
	public ReturnCode run(boolean enableLogging) {
		// Set CPU usage
        try {
            _cpuUsage = CPUUsage.getDefaultInstance();
        } catch (InvalidStateException ex) {
            System.out.println("Run Info: CPU usage currently disabled.");
        }
	if (enableLogging) {
		try {
			String level = _props.getStringProperty(RuntimeProperties.LOG_LEVEL);
			Level logLevel = null;

			if (level.equalsIgnoreCase("debug")) {
				logLevel = Level.DEBUG;
			} else if (level.equalsIgnoreCase("info")) {
				logLevel = Level.INFO;
			} else if (level.equalsIgnoreCase("notice")) {
				// Doesn't support notice so use warn
				logLevel = Level.WARN;
			} else if (level.equalsIgnoreCase("warn")) {
				logLevel = Level.WARN;
			} else if (level.equalsIgnoreCase("error")) {
				logLevel = Level.ERROR;
			} else {
				System.out.println("Log level " + level + " not understood");
			}
			if (logLevel != null) {
				Configurator.setLevel("com.solacesystems", logLevel);
				Configurator.setLevel("com.solace", logLevel);
				Configurator.setRootLevel(logLevel);		
			}
		} catch (RuntimeException re) {
				System.out.println("Could not set log level due to: " + re);
		}
	}
		Trace.info("--- sdkperf startup ---");
		Thread.currentThread().setName("SDKPerf_mainthread");

		// Check if we need to print Version
		if (_props.getProperty(RuntimeProperties.WANT_VERSIONPRINT) != null
				&& _props.getBooleanProperty(RuntimeProperties.WANT_VERSIONPRINT)) {

			try {
				System.out.println(ClientFactory.getVersionString(_props));
			} catch (Exception e) {
				System.out.println("E: Getting version info failed.");
				e.printStackTrace();
			}
			return ReturnCode.SUCCESS;
		}
		dumpProperties(_props);
		
		// Reset pub/sub rate data points.
		pubRatePoints.clear();
		subRatePoints.clear();
		
        // Output some info on naming used.
        System.out.println(generateNamingInfo());
        // Output enviornment details
        System.out.println("> VM Name: " + System.getProperty("java.vm.name"));
        System.out.println("> Timing Package Clock Speed (Hz): " + Timing.clockSpeedInHz());
        
		// init publishers
		System.out.println("> Getting ready to init clients");
		try {
			// See if we can measure cpu
			CPUUsage cpuMon = null;
			try {
				cpuMon = CPUUsage.newInstance(port);
	        } catch (InvalidStateException ex) {
	        	Trace.debug("CPU Usage disabled");
	        }
	        ToolMode tm = (ToolMode) _props.getProperty(RuntimeProperties.TOOL_MODE);
            
	        // Create the client collection if it doesn't exist
	        if(_clientCollection == null) {
	            if (tm == ToolMode.SDKPERF) {
	                _clientCollection = new SdkperfClientCollection(_props, cpuMon);
	            } else {
	                _clientCollection = new RtrperfClientCollection(_props, cpuMon);
	            }
	        }

			_clientCollection.connect();
			
			_clientTransactedSessionNames.clear();
			if (_props.getIntegerProperty(RuntimeProperties.AD_TRANSACTION_SIZE) > 0) {
				int numclients = _props.getIntegerProperty(RuntimeProperties.NUM_CLIENTS).intValue();
				int pubsPerSession = _props.getIntegerProperty(RuntimeProperties.NUM_PUBS_PER_SESSION);
				for (int i = 0; i < numclients; i++) {
					if (_props.getBooleanProperty(RuntimeProperties.AD_WANT_XA_TRANSACTION) == true) {
						// Create Consumer XA Session
						String clientTransactedSessionName = _clientCollection.openXaSession(i, _props);
						if ((_props.getIntegerProperty(RuntimeProperties.XA_TRANSACTION_IDLE_TIMEOUT) > 0)) {
							_clientCollection.setXaTransactionTimeout(clientTransactedSessionName,
																		_props.getIntegerProperty(RuntimeProperties.XA_TRANSACTION_IDLE_TIMEOUT),
																		i);
						}
						_clientTransactedSessionNames.add(clientTransactedSessionName);
						_clientXids.add(_clientCollection.initXaTransactions(clientTransactedSessionName, i));
					} else {
						_clientTransactedSessionNames.add(
								_clientCollection.openTransactedSession(i, _props));
					}
				}
				
				List<String> pubTransactedSessionNames = new Vector<String>();
				for (int i = 0; i < numclients; i++) {
					for (int j = 0; j < pubsPerSession; j++) {
						if (_props.getBooleanProperty(RuntimeProperties.AD_WANT_PRODUCER_CONSUMERS_TRANSACTION) == true) {
							// Do not create another XA Session if using Consumer XA Session
							pubTransactedSessionNames.add(_clientTransactedSessionNames.get(i));
						} else if (_props.getBooleanProperty(RuntimeProperties.AD_WANT_XA_TRANSACTION) == true) {
							// Create Producer XA Session
							String pubTransactedSessionName = _clientCollection.openXaSession(i, _props);
							if ((_props.getIntegerProperty(RuntimeProperties.XA_TRANSACTION_IDLE_TIMEOUT) > 0)) {
								_clientCollection.setXaTransactionTimeout(pubTransactedSessionName,
																			_props.getIntegerProperty(RuntimeProperties.XA_TRANSACTION_IDLE_TIMEOUT),
																			i);
							}
							pubTransactedSessionNames.add(pubTransactedSessionName);
						} else {
							pubTransactedSessionNames.add(
									_clientCollection.openTransactedSession(i, _props));
						}
					}
				}
				_props.setProperty(RuntimeProperties.TRANSACTED_SESSION_NAME_LIST, pubTransactedSessionNames);
			}
		} 
		catch (Exception e) {
			System.out.println("E: Initialization error: client creation failed.");
			e.printStackTrace();
			return ReturnCode.FAILURE;
		}

		// If we're running inside of J2EE app server we don't want any threads running after run() completes
		if (fromMain == true) {
			Runtime.getRuntime().addShutdownHook(new Thread() {
				@Override
				public void run() {
					shutdownHook();
				}
			});
		}
		
		// Check to see if we have subscriptions to add
		if(verifySubscriptions() == ReturnCode.FAILURE)
			return ReturnCode.FAILURE;
		
		// Start client(s)
		if(startClients() == ReturnCode.FAILURE)
			return ReturnCode.FAILURE;
		
		// Check to see if we need to publish messages
		if(startPublishers() == ReturnCode.FAILURE)
			return ReturnCode.FAILURE;
		
		// Check to see if we need to send cache requests
		if(cacheRequest() == ReturnCode.FAILURE)
			return ReturnCode.FAILURE;

		// Fall in the pubLoop which will return when all publishers are done
		if(pubLoop() == ReturnCode.FAILURE)
			return ReturnCode.FAILURE;
		
		// Go through the shutdown hook
		if(!fromMain)
			shutdownHook();

		Integer tst = _props.getIntegerProperty(RuntimeProperties.TRACE_SLEEP_TIME);
		if (tst != null && tst > 0) {
			try {
				System.out.println("Sleeping for tracing for " + tst + " seconds");
				Thread.sleep(tst * 1000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}

		System.out.println("======>Main thread finish");
		return ReturnCode.SUCCESS;
	}
	
	private ReturnCode verifySubscriptions() {
		System.out.println("> Adding subscriptions if required");
		try {	
			if (_props.getBooleanProperty(RuntimeProperties.WANT_QUEUE_BROWSER)) {
				// All clients must browse on the same queues in cli version of sdkperf.  So pick client 0.
				@SuppressWarnings("unchecked")
				List < List<String>> strQueues = (List<List<String>>) _props.getProperty(RuntimeProperties.SUB_QUEUE_LISTS);
                List<String> queuesList = strQueues.get(0);
                String selector = null;
                @SuppressWarnings("unchecked")
				List < List<String>> strSelectors = (List<List<String>>) _props.getProperty(RuntimeProperties.SELECTOR_LIST);
                if (strSelectors != null) {
                	List<String> selectors = strSelectors.get(0);
                	selector = selectors.get(0);
                }
				
                _clientCollection.startQueueBrowsing(queuesList, selector, _props);
			} else if (_props.getIntegerProperty(RuntimeProperties.BIND_RATE) <= 0) {
				addSubscriptions();
			}

			if (_props.getIntegerProperty(RuntimeProperties.BIND_RATE) > 0) {
				List<List<String>> strQueues, strTopics, strDtes, strSelectors;
				strQueues = (List<List<String>>) _props.getProperty(RuntimeProperties.SUB_QUEUE_LISTS);
				strTopics = (List<List<String>>) _props.getProperty(RuntimeProperties.SUB_TOPIC_LISTS);
				strDtes = (List<List<String>>) _props.getProperty(RuntimeProperties.SUB_DTE_LISTS);
				strSelectors = (List<List<String>>) _props.getProperty(RuntimeProperties.SELECTOR_LIST);
				List<String> selectors = null;
				if (strSelectors != null) {
					selectors = strSelectors.get(0);
				}
				if (strQueues.size() > 0) {
					EpConfigProperties epProps = EpConfigProperties.CreateForQueueEpAdd(
							strQueues.get(0),
							selectors,
							_props.getBooleanProperty(RuntimeProperties.WANT_NO_LOCAL),
							"",
							_props.getIntegerProperty(RuntimeProperties.AD_TRANSACTION_SIZE),
							_props.getIntegerProperty(RuntimeProperties.DISCARD_NOTIFY_SENDER),
							_props.getBooleanProperty(RuntimeProperties.AD_WANT_ACTIVE_FLOW_INDICATION),
							_props.getBooleanProperty(RuntimeProperties.WANT_MESSAGE_PRIORITY_ORDER_CHECKING),
							_props.getBooleanProperty(RuntimeProperties.WANT_REPLAY),
							_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_DATE),
							_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_MSG_ID),
							_props.getIntegerProperty(RuntimeProperties.RECONNECT_TRIES),
							_props.getIntegerProperty(RuntimeProperties.RECONNECT_RETRY_INTERVAL_IN_MSECS),
							_props.getBooleanProperty(RuntimeProperties.WANT_PROVISIONED_ENDPOINT),
							(EndpointAccessType) _props.getProperty(RuntimeProperties.PE_ACCESS_TYPE));
					epProps.setBindRate(_props.getIntegerProperty(RuntimeProperties.BIND_RATE));
					epProps.setFlowFlapCount(_props.getIntegerProperty(RuntimeProperties.FLOW_FLAP_COUNT));
					_clientCollection.startBinding(epProps);
				} else if (strTopics.size() > 0 && strDtes.size() > 0) {
					EpConfigProperties epProps = EpConfigProperties.CreateForTopicEpAdd(
							strDtes.get(0),
							strTopics.get(0),
							selectors,
							_props.getBooleanProperty(RuntimeProperties.WANT_NO_LOCAL),
							"",
							_props.getIntegerProperty(RuntimeProperties.AD_TRANSACTION_SIZE),
							_props.getIntegerProperty(RuntimeProperties.DISCARD_NOTIFY_SENDER),
							_props.getBooleanProperty(RuntimeProperties.WANT_REPLAY),
							_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_DATE),
							_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_MSG_ID),
							_props.getIntegerProperty(RuntimeProperties.RECONNECT_TRIES),
							_props.getIntegerProperty(RuntimeProperties.RECONNECT_RETRY_INTERVAL_IN_MSECS));
					epProps.setBindRate(_props.getIntegerProperty(RuntimeProperties.BIND_RATE));
					epProps.setFlowFlapCount(_props.getIntegerProperty(RuntimeProperties.FLOW_FLAP_COUNT));
					epProps.setIsTopic(true);
					_clientCollection.startBinding(epProps);
				}
			}
		} catch (Exception e) {
			System.out.println("E: Initialization error: subscriptions addition failed.");
			System.out.println(e.getMessage());
			e.printStackTrace();
			return ReturnCode.FAILURE;
		}
		
		return ReturnCode.SUCCESS;
	}
	
	private ReturnCode startClients() {
		
		System.out.println("> Getting ready to start clients.");
		try {
			// Must now start them all too.
			_clientCollection.start();
		} catch (PubSubException e) {
			Trace.error(e.getMessage(), e);
			System.out.println("E: Start error:");
			e.printStackTrace();
			return ReturnCode.FAILURE;
		}
		return ReturnCode.SUCCESS;
	}
	
	private ReturnCode startPublishers() {
		
		if (_props.getLongProperty(RuntimeProperties.NUM_MSGS_TO_PUBLISH) > 0)
		{
			System.out.println("> Starting publish.");
			try {
				_clientCollection.startPublishing(_props);
			} catch (Exception e) {
				Trace.error(e.getMessage(), e);
				System.out.println("E: publishing stopped due to exception:");
				e.printStackTrace();
				return ReturnCode.FAILURE;
			}
		}
		
		return ReturnCode.SUCCESS;
	}
	
	private ReturnCode cacheRequest() {
		
		if (_props.getIntegerProperty(RuntimeProperties.CACHE_NUM_REQ) > 0)
		{
			System.out.println("> Starting cache requesting.");
			try {
				@SuppressWarnings("unchecked")
				List<String> strTopics = (List<String>) _props.getProperty(RuntimeProperties.PUBLISH_TOPIC_LIST);
				
				// Always look at topics list 0 for cache requests.  
				// Keep it simple.
				_clientCollection.startCacheRequesting(strTopics, _props);
			} catch (Exception e) {
				Trace.error(e.getMessage(), e);
				System.out.println("E: Caught exception while cache requesting:");
				e.printStackTrace();
				return ReturnCode.FAILURE;
			}
		}
		return ReturnCode.SUCCESS;
	}
	
	// Returns when all publishers are done.
	private ReturnCode pubLoop() {

		while (true) {
			
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
			}
			
			double cpuUsage = 0;
			if (_cpuUsage != null) {
				cpuUsage = _cpuUsage.resetIntervalAndGetCpuUsage();
			}
			try {

				long pubrate_i = 0, subrate_i = 0;
				if (_clientCollection != null) {
					if (!_props.getBooleanProperty(RuntimeProperties.WANT_QUIET)) {
						pubrate_i = snapshotStats(
								pubRatePoints,
								_clientCollection
										.getSdkStat(
												GenericStatType.TOTAL_MSGS_SENT,
												SdkperfClientCollection.ALL_CLIENT_INDEX));
						subrate_i = snapshotStats(
								subRatePoints,
								_clientCollection
										.getSdkStat(
												GenericStatType.TOTAL_MSGS_RECVED,
												SdkperfClientCollection.ALL_CLIENT_INDEX));
						System.out.print(StatFormatHelper.getFmtInstantStats(
								_clientCollection, pubrate_i, subrate_i,
								cpuUsage)
								+ "\r\n");
					}
				}
			} catch (PubSubException e) {
				Trace.error(e.getMessage(), e);
				System.out.println("E: query stats stopped due to error:");
				e.printStackTrace();
				return ReturnCode.FAILURE;
			}

			// If all pubs are dead, exit loop
			if (_props.getLongProperty(RuntimeProperties.NUM_MSGS_TO_PUBLISH)
					.longValue() > 0) {
				if (_clientCollection != null
						&& !_clientCollection.isPublishing()) {
					break;
				}
			} else if (_props
					.getIntegerProperty(RuntimeProperties.CACHE_NUM_REQ) > 0) {
				if (_clientCollection != null
						&& !_clientCollection.isCacheRequesting()) {
					break;
				}
			} else if (_props
					.getBooleanProperty(RuntimeProperties.WANT_QUEUE_BROWSER)) {
				if (_clientCollection != null
						&& !_clientCollection.isQueueBrowsing()) {
					break;
				}
			} else if (_props.getIntegerProperty(RuntimeProperties.BIND_RATE) > 0) {
				if (!_props.getBooleanProperty(RuntimeProperties.WAIT_AFTER_BIND)) {
					if (_clientCollection != null && !_clientCollection.isBinding()) {
						break;
					}
				}
			} else {
				if (_clientCollection != null && !_clientCollection.isConnected()) {
					break;
				}
			}
		}
		
		return ReturnCode.SUCCESS;
	}

	@SuppressWarnings("unchecked")
	private void addSubscriptions() throws Exception {
		// Subscription setup (load up either topics or xpes from
		// properties)
		int numclients = _props.getIntegerProperty(RuntimeProperties.NUM_CLIENTS).intValue();
		
		boolean wantSubscriptionRateStats = 
			_props.getBooleanProperty(RuntimeProperties.WANT_SUBSCRIPTION_RATE_STATS);
		
		List<List<String>> strQueues, strTopics, strDtes, strSelectors;
		strQueues = (List<List<String>>) _props.getProperty(RuntimeProperties.SUB_QUEUE_LISTS);
		strTopics = (List<List<String>>) _props.getProperty(RuntimeProperties.SUB_TOPIC_LISTS);
		strDtes = (List<List<String>>) _props.getProperty(RuntimeProperties.SUB_DTE_LISTS);
		strSelectors = (List<List<String>>)  _props.getProperty(RuntimeProperties.SELECTOR_LIST);

		for (int i = 0; i < numclients; i++) {
			
			String transactedSessionName = "";
			if (_props.getIntegerProperty(RuntimeProperties.AD_TRANSACTION_SIZE) > 0) {
				// If ad transacted session size is bigger than 0 then we want transactions.  So create all flows
				// inside of a transaction.
				transactedSessionName = _clientTransactedSessionNames.get(i);
			}
			
			long startTime = Timing.getClockValue();
			long numSubscriptions = 0;
			
			boolean isAdding = true;
			
			if (_props.getIntegerProperty(RuntimeProperties.NUM_TEMP_QUEUE_ENDPOINTS).intValue() > 0) {
				numSubscriptions = _props.getIntegerProperty(RuntimeProperties.NUM_TEMP_QUEUE_ENDPOINTS).intValue();
				List<String> selectors = null;
				if (strSelectors != null) {
					selectors = strSelectors.get(i % strSelectors.size());
				}
				List<String> queueNames = null;
				if (strQueues != null) {
					queueNames = strQueues.get(i % strQueues.size());
				}
				EpConfigProperties epProps = EpConfigProperties.CreateForTempEpAdd(
						SubscriberDestinationsType.QUEUE,
						_props.getIntegerProperty(RuntimeProperties.NUM_TEMP_QUEUE_ENDPOINTS).intValue(),
						null,
						selectors,
						_props.getIntegerProperty(RuntimeProperties.PE_MAXMSG_SIZE),
						_props.getIntegerProperty(RuntimeProperties.PE_QUOTA_MB),
						_props.getStringProperty(RuntimeProperties.PE_PERMISSION),
						_props.getIntegerProperty(RuntimeProperties.PE_RESPECT_TTL),
						_props.getBooleanProperty(RuntimeProperties.WANT_NO_LOCAL),
						transactedSessionName,
						_props.getIntegerProperty(RuntimeProperties.AD_TRANSACTION_SIZE),
						_props.getIntegerProperty(RuntimeProperties.DISCARD_NOTIFY_SENDER),
						_props.getIntegerProperty(RuntimeProperties.PE_MAX_MSG_REDELIVERY),
						_props.getBooleanProperty(RuntimeProperties.AD_WANT_ACTIVE_FLOW_INDICATION),
						_props.getBooleanProperty(RuntimeProperties.WANT_MESSAGE_PRIORITY_ORDER_CHECKING),
						_props.getBooleanProperty(RuntimeProperties.WANT_REPLAY),
						_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_DATE),
						_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_MSG_ID),
						_props.getIntegerProperty(RuntimeProperties.RECONNECT_TRIES),
						_props.getIntegerProperty(RuntimeProperties.RECONNECT_RETRY_INTERVAL_IN_MSECS),
						queueNames
						);
				
				List<String> endpoints = _clientCollection.tempEndpointUpdate(epProps, i);
				
                // If user has specified queues and topics then topic to queue mapping is desired.  
				// therefore add the clients topics to each queue of the client 
				if (strTopics != null && !strTopics.isEmpty()) {
					for (String queue : endpoints) {
						_clientCollection.mapTopics(queue, strTopics.get(i % strTopics.size()), true, i);
					}
				} else {
					// Only publish to the temp queues if the user didn't map any topics.
					_props.setProperty(RuntimeProperties.PUBLISH_QUEUE_LIST, endpoints);
				}

			} else if (_props.getIntegerProperty(RuntimeProperties.NUM_TEMP_TOPIC_ENDPOINTS).intValue() > 0) {

				List<String> topics = null;
				if (strTopics != null) {
					topics = strTopics.get(i % strTopics.size());
				}
				
				List<String> selectors = null;
				if (strSelectors != null) {
					selectors = strSelectors.get(i % strSelectors.size());
				}
				List<String> teNames = null;
				if (strDtes != null) {
					teNames = strDtes.get(i % strDtes.size());
				}
				numSubscriptions = _props.getIntegerProperty(RuntimeProperties.NUM_TEMP_TOPIC_ENDPOINTS).intValue();
				
				EpConfigProperties epProps = EpConfigProperties.CreateForTempEpAdd(
						SubscriberDestinationsType.TOPIC,
						_props.getIntegerProperty(RuntimeProperties.NUM_TEMP_TOPIC_ENDPOINTS).intValue(),
						topics,
						selectors,
						_props.getIntegerProperty(RuntimeProperties.PE_MAXMSG_SIZE),
						_props.getIntegerProperty(RuntimeProperties.PE_QUOTA_MB),
						_props.getStringProperty(RuntimeProperties.PE_PERMISSION),
						_props.getIntegerProperty(RuntimeProperties.PE_RESPECT_TTL),
						_props.getBooleanProperty(RuntimeProperties.WANT_NO_LOCAL),
						transactedSessionName,
						_props.getIntegerProperty(RuntimeProperties.AD_TRANSACTION_SIZE),
						_props.getIntegerProperty(RuntimeProperties.DISCARD_NOTIFY_SENDER),
						_props.getIntegerProperty(RuntimeProperties.PE_MAX_MSG_REDELIVERY),
						_props.getBooleanProperty(RuntimeProperties.AD_WANT_ACTIVE_FLOW_INDICATION),
						_props.getBooleanProperty(RuntimeProperties.WANT_MESSAGE_PRIORITY_ORDER_CHECKING),
						_props.getBooleanProperty(RuntimeProperties.WANT_REPLAY),
						_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_DATE),
						_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_MSG_ID),
						_props.getIntegerProperty(RuntimeProperties.RECONNECT_TRIES),
						_props.getIntegerProperty(RuntimeProperties.RECONNECT_RETRY_INTERVAL_IN_MSECS),
						teNames
						);
				_clientCollection.tempEndpointUpdate(epProps, i);
			} else if (strQueues != null && strQueues.size() > 0) {

				if (_props.getBooleanProperty(RuntimeProperties.WANT_PROVISIONED_ENDPOINT)) {
					EpConfigProperties epProps = EpConfigProperties.CreateForEpProvision(
							strQueues.get(i % strQueues.size()),
							false,
							isAdding,
							(EndpointAccessType) _props.getProperty(RuntimeProperties.PE_ACCESS_TYPE),
							_props.getIntegerProperty(RuntimeProperties.PE_MAXMSG_SIZE),
							_props.getIntegerProperty(RuntimeProperties.PE_QUOTA_MB),
							_props.getStringProperty(RuntimeProperties.PE_PERMISSION),
							_props.getIntegerProperty(RuntimeProperties.PE_RESPECT_TTL),
							_props.getIntegerProperty(RuntimeProperties.DISCARD_NOTIFY_SENDER),
							_props.getIntegerProperty(RuntimeProperties.PE_MAX_MSG_REDELIVERY),
							_props.getBooleanProperty(RuntimeProperties.AD_WANT_ACTIVE_FLOW_INDICATION));
							
					_clientCollection.endpointProvisioning(epProps, i);
				}
				
				numSubscriptions = strQueues.get(i % strQueues.size()).size();
				List<String> tempSelector = null;
				if(strSelectors != null) {
					tempSelector = strSelectors.get(i % strSelectors.size());
				} 
				
				EpConfigProperties epProps = EpConfigProperties.CreateForQueueEpAdd(
						strQueues.get(i % strQueues.size()),
						tempSelector,
						_props.getBooleanProperty(RuntimeProperties.WANT_NO_LOCAL),
						transactedSessionName,
						_props.getIntegerProperty(RuntimeProperties.AD_TRANSACTION_SIZE),
						_props.getIntegerProperty(RuntimeProperties.DISCARD_NOTIFY_SENDER),
						_props.getBooleanProperty(RuntimeProperties.AD_WANT_ACTIVE_FLOW_INDICATION),
						_props.getBooleanProperty(RuntimeProperties.WANT_MESSAGE_PRIORITY_ORDER_CHECKING),
						_props.getBooleanProperty(RuntimeProperties.WANT_REPLAY),
						_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_DATE),
						_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_MSG_ID),
						_props.getIntegerProperty(RuntimeProperties.RECONNECT_TRIES),
						_props.getIntegerProperty(RuntimeProperties.RECONNECT_RETRY_INTERVAL_IN_MSECS),
						_props.getBooleanProperty(RuntimeProperties.WANT_PROVISIONED_ENDPOINT),
						(EndpointAccessType) _props.getProperty(RuntimeProperties.PE_ACCESS_TYPE)
						);
				// If user has specified queues and topics then topic to queue mapping is desired.
				// Therefore, add the clients topics to each queue of the client

				// If user wants to subscribe first apply the topics before binding
				if (_props.getBooleanProperty(RuntimeProperties.WANT_SUB_FIRST)) {

					if (strTopics != null && !strTopics.isEmpty()) {
						for (String queue : strQueues.get(i % strQueues.size())) {
							_clientCollection.mapTopics(queue, strTopics.get(i % strTopics.size()), true, i);
						}
					}

					_clientCollection.queueUpdate(epProps, i);
				} else {
					_clientCollection.queueUpdate(epProps, i);

					if (strTopics != null && !strTopics.isEmpty()) {
						for (String queue : strQueues.get(i % strQueues.size())) {
							_clientCollection.mapTopics(queue, strTopics.get(i % strTopics.size()), true, i);
						}
					}
				}

			} else if (strTopics != null && !strTopics.isEmpty() && strDtes != null && !strDtes.isEmpty()) {
				if (_props.getBooleanProperty(RuntimeProperties.WANT_PROVISIONED_ENDPOINT)) {
					
					EpConfigProperties epProps = EpConfigProperties.CreateForEpProvision(
							strDtes.get(i % strDtes.size()), 
							true,
							isAdding,
							(EndpointAccessType) _props.getProperty(RuntimeProperties.PE_ACCESS_TYPE),
							_props.getIntegerProperty(RuntimeProperties.PE_MAXMSG_SIZE),
							_props.getIntegerProperty(RuntimeProperties.PE_QUOTA_MB),
							_props.getStringProperty(RuntimeProperties.PE_PERMISSION),
							_props.getIntegerProperty(RuntimeProperties.PE_RESPECT_TTL),
							_props.getIntegerProperty(RuntimeProperties.DISCARD_NOTIFY_SENDER),
							_props.getIntegerProperty(RuntimeProperties.PE_MAX_MSG_REDELIVERY),
							_props.getBooleanProperty(RuntimeProperties.AD_WANT_ACTIVE_FLOW_INDICATION));
					
					_clientCollection.endpointProvisioning(epProps, i);
				}
				
				numSubscriptions = strTopics.get(i % strTopics.size()).size();
				List<String> tempSelector = null;
				if(strSelectors != null) {
					tempSelector = strSelectors.get(i % strSelectors.size());
				}
				
				EpConfigProperties epProps = EpConfigProperties.CreateForTopicEpAdd(
						strDtes.get(i % strDtes.size()), 
						strTopics.get(i % strTopics.size()),
						tempSelector,
						_props.getBooleanProperty(RuntimeProperties.WANT_NO_LOCAL),
						transactedSessionName,
						_props.getIntegerProperty(RuntimeProperties.AD_TRANSACTION_SIZE),
						_props.getIntegerProperty(RuntimeProperties.DISCARD_NOTIFY_SENDER),
						_props.getBooleanProperty(RuntimeProperties.WANT_REPLAY),
						_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_DATE),
						_props.getStringProperty(RuntimeProperties.WANT_REPLAY_FROM_MSG_ID),
						_props.getIntegerProperty(RuntimeProperties.RECONNECT_TRIES),
						_props.getIntegerProperty(RuntimeProperties.RECONNECT_RETRY_INTERVAL_IN_MSECS)
						);
				
				_clientCollection.topicUpdate(epProps, i);
			} else if (strTopics != null && !strTopics.isEmpty()) {
				// Didn't specify any DTEs.
				numSubscriptions = strTopics.get(i % strTopics.size()).size();
				
				AbstractCacheLiveDataAction lda = AbstractCacheLiveDataAction.QUEUE;
				
				if (_props.getProperty(RuntimeProperties.CACHE_LIVE_DATA_ACTION) != null) {
					lda = (AbstractCacheLiveDataAction)_props.getProperty(RuntimeProperties.CACHE_LIVE_DATA_ACTION);
				}

				if (_props.getProperty(RuntimeProperties.CACHE_WANT_REQUESTS_ON_SUBSCRIBE) != null &&
						_props.getBooleanProperty(RuntimeProperties.CACHE_WANT_REQUESTS_ON_SUBSCRIBE)) {
					boolean subscribe = true;
					boolean waitForConfirm = true;
					
					// For now, sequence ranges are only supported in limited conditions against SolCache-RS servers
					// as such, disable them here.
					long minSeq = -1;
					long maxSeq = -1;
					
					_clientCollection.cacheRequest(
							strTopics.get(i % strTopics.size()),
							subscribe, 
							lda, 
							waitForConfirm,
							minSeq,
							maxSeq,
							i);
				} else {
					EpConfigProperties epProps = EpConfigProperties.CreateForSubscriptionAdd(
							strTopics.get(i % strTopics.size()),
							_props.getStringProperty(RuntimeProperties.SUBSCRIPTION_CLIENT_NAME),
							SubscriberDestinationsType.TOPIC
							);

					_clientCollection.subscriptionUpdate(epProps, i);
				}
			} else if (strDtes != null && !strDtes.isEmpty() && (strTopics == null || strTopics.isEmpty())) {
				System.out.println("E: Must specify topics when binding to durable topic endpoints.");
			}
			
			long stopTime = Timing.getClockValue();
			
			if (wantSubscriptionRateStats) {
                double timeDiffInUsec = (stopTime - startTime) / Timing.microSecDivisor();
                double subRate = 0;
                if (timeDiffInUsec > 0) {
                    subRate = ((double)(numSubscriptions*1000000.0)) / timeDiffInUsec;
                }
                
				System.out.println(String.format(
						"%s: Added %d subscriptions, time = %.2f us, rate = %.2f subscriptions/sec%n",
						_clientCollection.getClientName(i), numSubscriptions, timeDiffInUsec, subRate));
			}
		}
	}
	
	private static void dumpProperties(RuntimeProperties rp) {
		if (rp.getBooleanProperty(RuntimeProperties.WANT_VERBOSE)) {
			System.out.println("> Dumping configuration:");
			System.out.println(rp.toString());
		}
	}
	
	private String generateNamingInfo() {
		
		StringBuilder sb = new StringBuilder();
		sb.append("Client naming used:");
		sb.append("\n\tlogging ID   = ");
        if (_props.getStringProperty(RuntimeProperties.CLIENT_USERNAME).equals("") &&
                (_props.getProperty(RuntimeProperties.AUTHENTICATION_SCHEME).equals(GenericAuthenticationScheme.CLIENT_CERTIFICATE) ||
                 _props.getProperty(RuntimeProperties.AUTHENTICATION_SCHEME).equals(GenericAuthenticationScheme.GSS_KRB))) {
            sb.append("router generated.");
        } else {
        	sb.append(ClientFactory.generateClientIdStr(_props, 1));
        }
        sb.append("\n\tusername     = " + ClientFactory.generateClientUsername(_props, 1));
        sb.append("\n\tvpn          = " + _props.getStringProperty(RuntimeProperties.CLIENT_VPN));
        sb.append("\n\tclient names = ");
        if (_props.getStringProperty(RuntimeProperties.CLIENT_NAME_PREFIX).equals("")) {
        	sb.append("sdk generated.");
        } else {
        	sb.append(ClientFactory.generateClientName(_props, 1));
        }
        sb.append("\n");
        
        return sb.toString();
	}

	private static long snapshotStats(Queue<RateDataPoint> q, long msgcount) {
		final int SMOOTHING_SAMPLES = 10;
		final RateDataPoint rdp = new RateDataPoint(msgcount, System.nanoTime());
		q.add(rdp);
		
		// compare with oldest point
		final RateDataPoint rdp_old = q.peek();
		if (q.size() == SMOOTHING_SAMPLES) {
			q.poll();
		}		
		
		long msgs = rdp.getMessageCount() - rdp_old.getMessageCount();
		long nanos = rdp.getTime() - rdp_old.getTime();
		return (nanos > 0) ? (msgs * 1000000000) / nanos : 0;
	}
	
	//refactor these two methods to have a common code in one place
	@SuppressWarnings("unchecked")
	private void shutdownHook() {
		System.out.println("> Running sdkperf shutdown...");
		
		if ((_clientCollection != null) && (_clientCollection.isPublishing())) {
			try {
				_clientCollection.stopPublishing();
			} catch (Exception e) {
				Trace.error("Failed to stop publishing.", e);
				e.printStackTrace();
			}
		}
		
		if ((_clientCollection != null) && (_clientCollection.isCacheRequesting())) {
			try {
				_clientCollection.stopCacheRequesting();
			} catch (Exception e) {
				Trace.error("Failed to stop cache requesting.", e);
				e.printStackTrace();
			}
		}
		
		if ((_clientCollection != null) && (_clientCollection.isQueueBrowsing())) {
			try {
				_clientCollection.stopQueueBrowsing();
			} catch (Exception e) {
				Trace.error("Failed to stop queue browsing.", e);
				e.printStackTrace();
			}
		}

		if ((_clientCollection != null) && (_clientCollection.isBinding())) {
			try {
				_clientCollection.stopBinding();
			} catch (Exception e) {
				Trace.error("Failed to stop bind thread.", e);
				e.printStackTrace();
			}
		}
		
//		BufferedDebugLog.instance().dumpToFile();
		
		if (_clientCollection != null && _clientCollection.isConnected()) {
			// Pause to allow subscribers to finish
			if (_props.getProperty(RuntimeProperties.PUBLISH_END_DELAY_IN_SEC) != null) {
				int pedMillis = _props
					.getIntegerProperty(RuntimeProperties.PUBLISH_END_DELAY_IN_SEC).intValue() * 1000;
				System.out.println(String.format("Pausing -ped time to allow clients to finish recv messages (%s ms)", pedMillis));
				try {
					Thread.sleep(pedMillis);
				} catch (InterruptedException e) {
				}
			}
			
			if (_props.getIntegerProperty(RuntimeProperties.SUB_MSG_QUEUE_DEPTH) > 0) {
				try {
					// Must stop flows first to avoid concurrent modification of the queue.
					// IE make message flow stop.
					_clientCollection.stop();
					int pedMillis = _props.getIntegerProperty(RuntimeProperties.PUBLISH_END_DELAY_IN_SEC).intValue() * 1000;
					System.out.println(String.format("Pausing -ped time to allow flows to stop (%s ms)", pedMillis));
					try {
						Thread.sleep(pedMillis);
					} catch (InterruptedException e) {
					}
					
					_clientCollection.clearKeptMsgs();
				} catch (Exception e) {
					Trace.error("Failed to clear kept messages.", e);
					e.printStackTrace();
				}
			}
			
			int numclients = _props.getIntegerProperty(RuntimeProperties.NUM_CLIENTS).intValue();
			
			if (_props.getIntegerProperty(RuntimeProperties.AD_TRANSACTION_SIZE) > 0) {
				// Clean up any client transactions.  
				for (int i = 0; i < numclients; i++) {
					try {
						
						boolean wantRollback = (_props.getIntegerProperty(RuntimeProperties.AD_TRANSACTION_ROLLBACK_INTERVAL) == 1);
						
						if (_props.getBooleanProperty(RuntimeProperties.AD_WANT_XA_TRANSACTION) == true) {
							_clientCollection.endXaSession(_clientTransactedSessionNames.get(i), _clientXids.get(i), XAResource.TMSUCCESS, i);
							boolean onePhase = _props.getBooleanProperty(RuntimeProperties.XA_WANT_ONE_PHASE_COMMIT_TRANSACTION);
							if (!onePhase) {
								_clientCollection.prepareXaSession(_clientTransactedSessionNames.get(i), _clientXids.get(i), i);
							}
							
							if(wantRollback) {
								_clientCollection.rollbackXaSession(_clientTransactedSessionNames.get(i), _clientXids.get(i), i);
							} else {
								_clientCollection.commitXaSession(_clientTransactedSessionNames.get(i), _clientXids.get(i), onePhase, i);
							}
						} else {
							_clientCollection.commitTransaction(_clientTransactedSessionNames.get(i), wantRollback, i);
						}
						
					} catch (PubSubException e) {
						Trace.error("Failed to commit transactions during cleanup.", e);
						e.printStackTrace();
					}
				}
			}
			
			try {
				_clientCollection.closeAllTransactedSessions(AbstractClientCollection.ALL_CLIENT_INDEX);
				if (_props.getBooleanProperty(RuntimeProperties.AD_WANT_XA_TRANSACTION) == true) {
					_clientCollection.closeAllXaSessions(AbstractClientCollection.ALL_CLIENT_INDEX);
				}
			} catch (Exception e) {
				Trace.error("Failed to close transactions during cleanup.", e);
				e.printStackTrace();
			}
			
			// If required try to remove subscriptions as well.
			// Also only remove subscriptions if you already added them.  When queue browsing, we 
			// skip adding the subscriptions so skip removing them as well.
			if (_props.getBooleanProperty(RuntimeProperties.WANT_REMOVE_SUBSCRIBER) &&
					!_props.getBooleanProperty(RuntimeProperties.WANT_QUEUE_BROWSER)	) {
				try {	
					// Subscription setup (load up either topics or xpes from
					// properties)
					
					List<List<String>> strQueues, strTopics, strDtes;
					strQueues = (List<List<String>>) _props.getProperty(RuntimeProperties.SUB_QUEUE_LISTS);
					strTopics = (List<List<String>>) _props.getProperty(RuntimeProperties.SUB_TOPIC_LISTS);
					strDtes = (List<List<String>>) _props.getProperty(RuntimeProperties.SUB_DTE_LISTS);
					boolean isAdding = false;
										
					if (strQueues != null && strQueues.size() > 0) {
						for (int i = 0; i < numclients; i++) {
							// If user has specified queues and topics then topic to queue mapping is desired.  
							// therefore add the clients topics to each queue of the client 
							if (strTopics != null && !strTopics.isEmpty()) {
								for (String queue : strQueues.get(i % strQueues.size())) {
									_clientCollection.mapTopics(queue, strTopics.get(i % strTopics.size()), false, i);
								}
							}
							EpConfigProperties epProps = EpConfigProperties.CreateForEpRemove(strQueues.get(i % strQueues.size()));
							
							_clientCollection.queueUpdate(epProps, i);
							
							// that is, if we wanted it provisioned originally, then at this point
							// we want to unprovision the endpoint
							if (_props.getBooleanProperty(RuntimeProperties.WANT_PROVISIONED_ENDPOINT)) {
								
								EpConfigProperties epProps2 = EpConfigProperties.CreateForEpProvision(
	        							strQueues.get(i % strQueues.size()),
	        							false,
	        							isAdding,
	        							(EndpointAccessType) _props.getProperty(RuntimeProperties.PE_ACCESS_TYPE),
	        							_props.getIntegerProperty(RuntimeProperties.PE_MAXMSG_SIZE),
	        							_props.getIntegerProperty(RuntimeProperties.PE_QUOTA_MB),
	        							_props.getStringProperty(RuntimeProperties.PE_PERMISSION),
	        							_props.getIntegerProperty(RuntimeProperties.PE_RESPECT_TTL),
	        							_props.getIntegerProperty(RuntimeProperties.DISCARD_NOTIFY_SENDER),
	        							_props.getIntegerProperty(RuntimeProperties.PE_MAX_MSG_REDELIVERY),
	        							_props.getBooleanProperty(RuntimeProperties.AD_WANT_ACTIVE_FLOW_INDICATION));
								
								_clientCollection.endpointProvisioning(epProps2, i);
							}
						}
					} else if (strTopics != null && strTopics.size() > 0 && strDtes != null && strDtes.size() > 0) {
						for (int i = 0; i < numclients; i++) {
							EpConfigProperties epProps = EpConfigProperties.CreateForEpRemove(strDtes.get(i % strDtes.size()));
							// Unsubscribe topic from the DTE when -nsr is not set
							epProps.setTopicUnsubscribe(true);
							_clientCollection.topicUpdate(epProps, i);
							
							if (_props.getBooleanProperty(RuntimeProperties.WANT_PROVISIONED_ENDPOINT)) {
								
								EpConfigProperties epProps2 = EpConfigProperties.CreateForEpProvision(
									strDtes.get(i % strDtes.size()), 
	        							true,
	        							isAdding,
	        							(EndpointAccessType) _props.getProperty(RuntimeProperties.PE_ACCESS_TYPE),
	        							_props.getIntegerProperty(RuntimeProperties.PE_MAXMSG_SIZE),
	        							_props.getIntegerProperty(RuntimeProperties.PE_QUOTA_MB),
	        							_props.getStringProperty(RuntimeProperties.PE_PERMISSION),
	        							_props.getIntegerProperty(RuntimeProperties.PE_RESPECT_TTL),
	        							_props.getIntegerProperty(RuntimeProperties.DISCARD_NOTIFY_SENDER),
	        							_props.getIntegerProperty(RuntimeProperties.PE_MAX_MSG_REDELIVERY),
	        							_props.getBooleanProperty(RuntimeProperties.AD_WANT_ACTIVE_FLOW_INDICATION));
								
								_clientCollection.endpointProvisioning(epProps2, i);
							}
						}
					} else if (strTopics != null && !strTopics.isEmpty() && !_props.getStringProperty(RuntimeProperties.SUBSCRIPTION_CLIENT_NAME).equals("")) {
						// For client subscriptions on behalf of, must also remove.
						for (int i = 0; i < numclients; i++) {
							EpConfigProperties epProps = EpConfigProperties.CreateForSubscriptionRemove(
									strTopics.get(i % strTopics.size()),
									_props.getStringProperty(RuntimeProperties.SUBSCRIPTION_CLIENT_NAME),
									SubscriberDestinationsType.TOPIC
									);
							
							_clientCollection.subscriptionUpdate(epProps, i);
						}
					} else if (_props.getIntegerProperty(RuntimeProperties.NUM_TEMP_QUEUE_ENDPOINTS).intValue() > 0 ||
							   _props.getIntegerProperty(RuntimeProperties.NUM_TEMP_TOPIC_ENDPOINTS).intValue() > 0) {
					
						for (int i = 0; i < numclients; i++) {
							_clientCollection.unbindAllTempEndpoints(i);
						}
					} else if (strTopics != null && !strTopics.isEmpty()
							&& _props.getStringProperty(RuntimeProperties.SUBSCRIPTION_CLIENT_NAME).length() == 0
							&& _props.getIntegerProperty(RuntimeProperties.NUM_TEMP_QUEUE_ENDPOINTS).intValue() <= 0
							&& _props.getIntegerProperty(RuntimeProperties.NUM_TEMP_TOPIC_ENDPOINTS).intValue() <= 0
							&& (strQueues == null || strQueues.size() == 0) && (strDtes == null || strDtes.size() == 0) ) {
						// Remove regular subscriptions
						for (int i = 0; i < numclients; i++) {
							EpConfigProperties epProps = EpConfigProperties.CreateForSubscriptionRemove(
									strTopics.get(i % strTopics.size()), "", SubscriberDestinationsType.TOPIC);
							_clientCollection.subscriptionUpdate(epProps, i);
						}
					}

				} catch (Exception e) {
					System.out.println("E: Cleanup error: subscription removal failed.");
					e.printStackTrace();
				}
			}
			
			try {
				_clientCollection.closeAllTransactedSessions(AbstractClientCollection.ALL_CLIENT_INDEX);
				if (_props.getBooleanProperty(RuntimeProperties.AD_WANT_XA_TRANSACTION) == true) {
					_clientCollection.closeAllXaSessions(AbstractClientCollection.ALL_CLIENT_INDEX);
				}
				_clientCollection.disconnect();
			} catch (Exception e) {
				Trace.error("Error occurred during client disconnect", e);
			}
		}

       
		try {
			if (_clientCollection != null) {
				System.out.println(StatFormatHelper.getFmtEndStats(
					_clientCollection, 
					_props,
					_clientCollection.getCpuUsage()));
			}
		} catch (PubSubException e) {
			Trace.error("Error printing stats", e);
			e.printStackTrace();
		}
		
		try {
            if (_clientCollection != null) {
                // We now destroy the clientcollection because it's the signal for Selenium
                // to close the browser windows that are still open.
                _clientCollection.destroy();
            }
		} catch (Exception e) {
            Trace.error("Error calling the final destroy() on the clientcollection", e);
        }
		
		
		MemoryUtils.printGcInformation();
		System.out.println("> Exiting");
	}
	

	private enum ReturnCode {
		SUCCESS, FAILURE
	}
	
}
