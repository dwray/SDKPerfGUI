/**
*  Copyright 2009-2015 Solace Systems, Inc. All rights reserved
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


import com.solacesystems.common.util.CPUUsage;
import com.solacesystems.common.util.InvalidStateException;
import com.solacesystems.pubsub.sdkperf.config.CliPropertiesParser;
import com.solacesystems.pubsub.sdkperf.config.EpConfigProperties;
import com.solacesystems.pubsub.sdkperf.config.RuntimeProperties;
import com.solacesystems.pubsub.sdkperf.core.AbstractCacheLiveDataAction;
import com.solacesystems.pubsub.sdkperf.core.AbstractClientCollection;
import com.solacesystems.pubsub.sdkperf.core.ClientFactory;
import com.solacesystems.pubsub.sdkperf.core.Constants;
import com.solacesystems.pubsub.sdkperf.core.GenericStatType;
import com.solacesystems.pubsub.sdkperf.core.PubSubException;
import com.solacesystems.pubsub.sdkperf.core.RtrperfClientCollection;
import com.solacesystems.pubsub.sdkperf.core.SdkperfClientCollection;
import com.solacesystems.pubsub.sdkperf.core.StatFormatHelper;
import com.solacesystems.pubsub.sdkperf.core.Constants.GenericAuthenticationScheme;
import com.solacesystems.pubsub.sdkperf.core.Constants.ToolMode;
import com.solacesystems.pubsub.sdkperf.util.DataTypes.SubscriberDestinationsType;
import com.solacesystems.pubsub.sdkperf.util.MemoryUtils;
import com.solacesystems.pubsub.sdkperf.util.RateDataPoint;
import com.solacesystems.pubsub.sdkperf.util.Timing;
import com.solacesystems.pubsub.sdkperf.util.DataTypes.SubscriberDestinationsType;

import javax.transaction.xa.XAResource;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Vector;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
		try {
			this._cpuUsage = CPUUsage.getDefaultInstance();
		} catch (InvalidStateException var14) {
			System.out.println("Run Info: CPU usage currently disabled.");
		}

		if (enableLogging) {
			try {
				String level = this._props.getStringProperty("LOG_LEVEL");
				Level logLevel = null;
				if (level.equalsIgnoreCase("debug")) {
					logLevel = Level.DEBUG;
				} else if (level.equalsIgnoreCase("info")) {
					logLevel = Level.INFO;
				} else if (level.equalsIgnoreCase("notice")) {
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
			} catch (RuntimeException var13) {
				System.out.println("Could not set log level due to: " + var13);
			}
		}

		Trace.info("--- sdkperf startup ---");
		Thread.currentThread().setName("SDKPerf_mainthread");
		if (this._props.getProperty("WANT_VERSIONPRINT") != null && this._props.getBooleanProperty("WANT_VERSIONPRINT")) {
			try {
				System.out.println(ClientFactory.getVersionString(this._props));
			} catch (Exception var10) {
				System.out.println("E: Getting version info failed.");
				var10.printStackTrace();
			}

			return ReturnCode.SUCCESS;
		} else {
			dumpProperties(this._props);
			this.pubRatePoints.clear();
			this.subRatePoints.clear();
			System.out.println(this.generateNamingInfo());
			System.out.println("> VM Name: " + System.getProperty("java.vm.name"));
			System.out.println("> Timing Package Clock Speed (Hz): " + Timing.clockSpeedInHz());
			System.out.println("> Getting ready to init clients");

			try {
				CPUUsage cpuMon = null;

				try {
					cpuMon = CPUUsage.newInstance(port);
				} catch (InvalidStateException var12) {
					Trace.debug("CPU Usage disabled");
				}

				Constants.ToolMode tm = (Constants.ToolMode)this._props.getProperty("TOOL_MODE");
				if (this._clientCollection == null) {
					if (tm == ToolMode.SDKPERF) {
						this._clientCollection = new SdkperfClientCollection(this._props, cpuMon);
					} else {
						this._clientCollection = new RtrperfClientCollection(this._props, cpuMon);
					}
				}

				this._clientCollection.connect();
				this._clientTransactedSessionNames.clear();
				if (this._props.getIntegerProperty("AD_TRANSACTION_SIZE") > 0) {
					int numclients = this._props.getIntegerProperty("NUM_CLIENTS");
					int pubsPerSession = this._props.getIntegerProperty("NUM_PUBS_PER_SESSION");

					for(int i = 0; i < numclients; ++i) {
						if (this._props.getBooleanProperty("AD_WANT_XA_TRANSACTION")) {
							String clientTransactedSessionName = this._clientCollection.openXaSession(i, this._props);
							if (this._props.getIntegerProperty("XA_TRANSACTION_IDLE_TIMEOUT") > 0) {
								this._clientCollection.setXaTransactionTimeout(clientTransactedSessionName, this._props.getIntegerProperty("XA_TRANSACTION_IDLE_TIMEOUT"), i);
							}

							this._clientTransactedSessionNames.add(clientTransactedSessionName);
							this._clientXids.add(this._clientCollection.initXaTransactions(clientTransactedSessionName, i));
						} else {
							this._clientTransactedSessionNames.add(this._clientCollection.openTransactedSession(i, this._props));
						}
					}

					List<String> pubTransactedSessionNames = new Vector();

					for(int i = 0; i < numclients; ++i) {
						for(int j = 0; j < pubsPerSession; ++j) {
							if (this._props.getBooleanProperty("AD_WANT_PRODUCER_CONSUMERS_TRANSACTION")) {
								pubTransactedSessionNames.add(this._clientTransactedSessionNames.get(i));
							} else if (this._props.getBooleanProperty("AD_WANT_XA_TRANSACTION")) {
								String pubTransactedSessionName = this._clientCollection.openXaSession(i, this._props);
								if (this._props.getIntegerProperty("XA_TRANSACTION_IDLE_TIMEOUT") > 0) {
									this._clientCollection.setXaTransactionTimeout(pubTransactedSessionName, this._props.getIntegerProperty("XA_TRANSACTION_IDLE_TIMEOUT"), i);
								}

								pubTransactedSessionNames.add(pubTransactedSessionName);
							} else {
								pubTransactedSessionNames.add(this._clientCollection.openTransactedSession(i, this._props));
							}
						}
					}

					this._props.setProperty("TRANSACTED_SESSION_NAME_LIST", pubTransactedSessionNames);
				}
			} catch (Exception var15) {
				System.out.println("E: Initialization error: client creation failed.");
				var15.printStackTrace();
				return ReturnCode.FAILURE;
			}

			if (fromMain) {
				Runtime.getRuntime().addShutdownHook(new Thread() {
					public void run() {
						SDKPerf_java_rmi.this.shutdownHook();
					}
				});
			}

			if (this.verifySubscriptions() == SDKPerf_java_rmi.ReturnCode.FAILURE) {
				return SDKPerf_java_rmi.ReturnCode.FAILURE;
			} else if (this.startClients() == SDKPerf_java_rmi.ReturnCode.FAILURE) {
				return SDKPerf_java_rmi.ReturnCode.FAILURE;
			} else if (this.startPublishers() == SDKPerf_java_rmi.ReturnCode.FAILURE) {
				return SDKPerf_java_rmi.ReturnCode.FAILURE;
			} else if (this.cacheRequest() == SDKPerf_java_rmi.ReturnCode.FAILURE) {
				return SDKPerf_java_rmi.ReturnCode.FAILURE;
			} else if (this.pubLoop() == SDKPerf_java_rmi.ReturnCode.FAILURE) {
				return SDKPerf_java_rmi.ReturnCode.FAILURE;
			} else {
				if (!fromMain) {
					this.shutdownHook();
				}

				Integer tst = this._props.getIntegerProperty("TRACE_SLEEP_TIME");
				if (tst != null && tst > 0) {
					try {
						System.out.println("Sleeping for tracing for " + tst + " seconds");
						Thread.sleep((long)(tst * 1000));
					} catch (InterruptedException var11) {
						throw new RuntimeException(var11);
					}
				}

				System.out.println("======>Main thread finish");
				return SDKPerf_java_rmi.ReturnCode.SUCCESS;
			}
		}
	}

	private ReturnCode verifySubscriptions() {
		System.out.println("> Adding subscriptions if required");

		try {
			List strQueues;
			List strTopics;
			List strSelectors;
			List selectors;
			if (this._props.getBooleanProperty("WANT_QUEUE_BROWSER")) {
				strQueues = (List)this._props.getProperty("SUB_QUEUE_LISTS");
				strTopics = (List)strQueues.get(0);
				String selector = null;
				strSelectors = (List)this._props.getProperty("SELECTOR_LIST");
				if (strSelectors != null) {
					selectors = (List)strSelectors.get(0);
					selector = (String)selectors.get(0);
				}

				this._clientCollection.startQueueBrowsing(strTopics, selector, this._props);
			} else if (this._props.getIntegerProperty("BIND_RATE") <= 0) {
				this.addSubscriptions();
			}

			if (this._props.getIntegerProperty("BIND_RATE") > 0) {
				strQueues = (List)this._props.getProperty("SUB_QUEUE_LISTS");
				strTopics = (List)this._props.getProperty("SUB_TOPIC_LISTS");
				List<List<String>> strDtes = (List)this._props.getProperty("SUB_DTE_LISTS");
				strSelectors = (List)this._props.getProperty("SELECTOR_LIST");
				selectors = null;
				if (strSelectors != null) {
					selectors = (List)strSelectors.get(0);
				}

				EpConfigProperties epProps;
				if (strQueues.size() > 0) {
					epProps = EpConfigProperties.CreateForQueueEpAdd((List)strQueues.get(0), selectors, this._props.getBooleanProperty("WANT_NO_LOCAL"), "", this._props.getIntegerProperty("AD_TRANSACTION_SIZE"), this._props.getIntegerProperty("DISCARD_NOTIFY_SENDER"), this._props.getBooleanProperty("AD_WANT_ACTIVE_FLOW_INDICATION"), this._props.getBooleanProperty("WANT_MESSAGE_PRIORITY_ORDER_CHECKING"), this._props.getBooleanProperty("WANT_REPLAY"), this._props.getStringProperty("WANT_REPLAY_FROM_DATE"), this._props.getStringProperty("WANT_REPLAY_FROM_MSG_ID"), this._props.getIntegerProperty("RECONNECT_TRIES"), this._props.getIntegerProperty("RECONNECT_RETRY_INTERVAL_IN_MSECS"), this._props.getBooleanProperty("WANT_PROVISIONED_ENDPOINT"), (Constants.EndpointAccessType)this._props.getProperty("PE_ACCESS_TYPE"));
					epProps.setBindRate(this._props.getIntegerProperty("BIND_RATE"));
					epProps.setFlowFlapCount(this._props.getIntegerProperty("FLOW_FLAP_COUNT"));
					this._clientCollection.startBinding(epProps);
				} else if (strTopics.size() > 0 && strDtes.size() > 0) {
					epProps = EpConfigProperties.CreateForTopicEpAdd((List)strDtes.get(0), (List)strTopics.get(0), selectors, this._props.getBooleanProperty("WANT_NO_LOCAL"), "", this._props.getIntegerProperty("AD_TRANSACTION_SIZE"), this._props.getIntegerProperty("DISCARD_NOTIFY_SENDER"), this._props.getBooleanProperty("WANT_REPLAY"), this._props.getStringProperty("WANT_REPLAY_FROM_DATE"), this._props.getStringProperty("WANT_REPLAY_FROM_MSG_ID"), this._props.getIntegerProperty("RECONNECT_TRIES"), this._props.getIntegerProperty("RECONNECT_RETRY_INTERVAL_IN_MSECS"));
					epProps.setBindRate(this._props.getIntegerProperty("BIND_RATE"));
					epProps.setFlowFlapCount(this._props.getIntegerProperty("FLOW_FLAP_COUNT"));
					epProps.setIsTopic(true);
					this._clientCollection.startBinding(epProps);
				}
			}
		} catch (Exception var7) {
			System.out.println("E: Initialization error: subscriptions addition failed.");
			System.out.println(var7.getMessage());
			var7.printStackTrace();
			return SDKPerf_java_rmi.ReturnCode.FAILURE;
		}

		return SDKPerf_java_rmi.ReturnCode.SUCCESS;
	}

	private ReturnCode startClients() {
		System.out.println("> Getting ready to start clients.");

		try {
			this._clientCollection.start();
		} catch (PubSubException var2) {
			Trace.error(var2.getMessage(), var2);
			System.out.println("E: Start error:");
			var2.printStackTrace();
			return SDKPerf_java_rmi.ReturnCode.FAILURE;
		}

		return SDKPerf_java_rmi.ReturnCode.SUCCESS;
	}

	private ReturnCode startPublishers() {
		if (this._props.getLongProperty("NUM_MSGS_TO_PUBLISH") > 0L) {
			System.out.println("> Starting publish.");

			try {
				this._clientCollection.startPublishing(this._props);
			} catch (Exception var2) {
				Trace.error(var2.getMessage(), var2);
				System.out.println("E: publishing stopped due to exception:");
				var2.printStackTrace();
				return SDKPerf_java_rmi.ReturnCode.FAILURE;
			}
		}

		return SDKPerf_java_rmi.ReturnCode.SUCCESS;
	}

	private ReturnCode cacheRequest() {
		if (this._props.getIntegerProperty("CACHE_NUM_REQ") > 0) {
			System.out.println("> Starting cache requesting.");

			try {
				List<String> strTopics = (List)this._props.getProperty("PUBLISH_TOPIC_LIST");
				this._clientCollection.startCacheRequesting(strTopics, this._props);
			} catch (Exception var2) {
				Trace.error(var2.getMessage(), var2);
				System.out.println("E: Caught exception while cache requesting:");
				var2.printStackTrace();
				return SDKPerf_java_rmi.ReturnCode.FAILURE;
			}
		}

		return SDKPerf_java_rmi.ReturnCode.SUCCESS;
	}

	private ReturnCode pubLoop() {
		while(true) {
			try {
				Thread.sleep(500L);
			} catch (InterruptedException var7) {
			}

			double cpuUsage = 0.0;
			if (this._cpuUsage != null) {
				cpuUsage = this._cpuUsage.resetIntervalAndGetCpuUsage();
			}

			try {
				long pubrate_i = 0L;
				long subrate_i = 0L;
				if (this._clientCollection != null && !this._props.getBooleanProperty("WANT_QUIET")) {
					pubrate_i = snapshotStats(this.pubRatePoints, this._clientCollection.getSdkStat(GenericStatType.TOTAL_MSGS_SENT, -1));
					subrate_i = snapshotStats(this.subRatePoints, this._clientCollection.getSdkStat(GenericStatType.TOTAL_MSGS_RECVED, -1));
					System.out.print(StatFormatHelper.getFmtInstantStats(this._clientCollection, pubrate_i, subrate_i, cpuUsage) + "\r\n");
				}
			} catch (PubSubException var8) {
				Trace.error(var8.getMessage(), var8);
				System.out.println("E: query stats stopped due to error:");
				var8.printStackTrace();
				return SDKPerf_java_rmi.ReturnCode.FAILURE;
			}

			if (this._props.getLongProperty("NUM_MSGS_TO_PUBLISH") > 0L) {
				if (this._clientCollection == null || this._clientCollection.isPublishing()) {
					continue;
				}
			} else if (this._props.getIntegerProperty("CACHE_NUM_REQ") > 0) {
				if (this._clientCollection == null || this._clientCollection.isCacheRequesting()) {
					continue;
				}
			} else if (this._props.getBooleanProperty("WANT_QUEUE_BROWSER")) {
				if (this._clientCollection == null || this._clientCollection.isQueueBrowsing()) {
					continue;
				}
			} else if (this._props.getIntegerProperty("BIND_RATE") > 0) {
				if (this._props.getBooleanProperty("WAIT_AFTER_BIND") || this._clientCollection == null || this._clientCollection.isBinding()) {
					continue;
				}
			} else if (this._clientCollection == null || this._clientCollection.isConnected()) {
				continue;
			}

			return SDKPerf_java_rmi.ReturnCode.SUCCESS;
		}
	}

	private void addSubscriptions() throws Exception {
		int numclients = this._props.getIntegerProperty("NUM_CLIENTS");
		boolean wantSubscriptionRateStats = this._props.getBooleanProperty("WANT_SUBSCRIPTION_RATE_STATS");
		List<List<String>> strQueues = (List)this._props.getProperty("SUB_QUEUE_LISTS");
		List<List<String>> strTopics = (List)this._props.getProperty("SUB_TOPIC_LISTS");
		List<List<String>> strDtes = (List)this._props.getProperty("SUB_DTE_LISTS");
		List<List<String>> strSelectors = (List)this._props.getProperty("SELECTOR_LIST");

		for(int i = 0; i < numclients; ++i) {
			String transactedSessionName = "";
			if (this._props.getIntegerProperty("AD_TRANSACTION_SIZE") > 0) {
				transactedSessionName = (String)this._clientTransactedSessionNames.get(i);
			}

			long startTime = Timing.getClockValue();
			long numSubscriptions = 0L;
			boolean isAdding = true;
			List tempSelector;
			List selectors;
			if (this._props.getIntegerProperty("NUM_TEMP_QUEUE_ENDPOINTS") > 0) {
				numSubscriptions = (long)this._props.getIntegerProperty("NUM_TEMP_QUEUE_ENDPOINTS");
				tempSelector = null;
				if (strSelectors != null) {
					tempSelector = (List)strSelectors.get(i % strSelectors.size());
				}

				selectors = null;
				if (strQueues != null) {
					selectors = (List)strQueues.get(i % strQueues.size());
				}

				EpConfigProperties epProps = EpConfigProperties.CreateForTempEpAdd(SubscriberDestinationsType.QUEUE, this._props.getIntegerProperty("NUM_TEMP_QUEUE_ENDPOINTS"), (List)null, tempSelector, this._props.getIntegerProperty("PE_MAXMSG_SIZE"), this._props.getIntegerProperty("PE_QUOTA_MB"), this._props.getStringProperty("PE_PERMISSION"), this._props.getIntegerProperty("PE_RESPECT_TTL"), this._props.getBooleanProperty("WANT_NO_LOCAL"), transactedSessionName, this._props.getIntegerProperty("AD_TRANSACTION_SIZE"), this._props.getIntegerProperty("DISCARD_NOTIFY_SENDER"), this._props.getIntegerProperty("PE_MAX_MSG_REDELIVERY"), this._props.getBooleanProperty("AD_WANT_ACTIVE_FLOW_INDICATION"), this._props.getBooleanProperty("WANT_MESSAGE_PRIORITY_ORDER_CHECKING"), this._props.getBooleanProperty("WANT_REPLAY"), this._props.getStringProperty("WANT_REPLAY_FROM_DATE"), this._props.getStringProperty("WANT_REPLAY_FROM_MSG_ID"), this._props.getIntegerProperty("RECONNECT_TRIES"), this._props.getIntegerProperty("RECONNECT_RETRY_INTERVAL_IN_MSECS"), selectors);
				List<String> endpoints = this._clientCollection.tempEndpointUpdate(epProps, i);
				if (strTopics != null && !strTopics.isEmpty()) {
					Iterator var18 = endpoints.iterator();

					while(var18.hasNext()) {
						String queue = (String)var18.next();
						this._clientCollection.mapTopics(queue, (List)strTopics.get(i % strTopics.size()), true, i);
					}
				} else {
					this._props.setProperty("PUBLISH_QUEUE_LIST", endpoints);
				}
			} else if (this._props.getIntegerProperty("NUM_TEMP_TOPIC_ENDPOINTS") > 0) {
				tempSelector = null;
				if (strTopics != null) {
					tempSelector = (List)strTopics.get(i % strTopics.size());
				}

				selectors = null;
				if (strSelectors != null) {
					selectors = (List)strSelectors.get(i % strSelectors.size());
				}

				List<String> teNames = null;
				if (strDtes != null) {
					teNames = (List)strDtes.get(i % strDtes.size());
				}

				numSubscriptions = (long)this._props.getIntegerProperty("NUM_TEMP_TOPIC_ENDPOINTS");
				EpConfigProperties epProps = EpConfigProperties.CreateForTempEpAdd(SubscriberDestinationsType.TOPIC, this._props.getIntegerProperty("NUM_TEMP_TOPIC_ENDPOINTS"), tempSelector, selectors, this._props.getIntegerProperty("PE_MAXMSG_SIZE"), this._props.getIntegerProperty("PE_QUOTA_MB"), this._props.getStringProperty("PE_PERMISSION"), this._props.getIntegerProperty("PE_RESPECT_TTL"), this._props.getBooleanProperty("WANT_NO_LOCAL"), transactedSessionName, this._props.getIntegerProperty("AD_TRANSACTION_SIZE"), this._props.getIntegerProperty("DISCARD_NOTIFY_SENDER"), this._props.getIntegerProperty("PE_MAX_MSG_REDELIVERY"), this._props.getBooleanProperty("AD_WANT_ACTIVE_FLOW_INDICATION"), this._props.getBooleanProperty("WANT_MESSAGE_PRIORITY_ORDER_CHECKING"), this._props.getBooleanProperty("WANT_REPLAY"), this._props.getStringProperty("WANT_REPLAY_FROM_DATE"), this._props.getStringProperty("WANT_REPLAY_FROM_MSG_ID"), this._props.getIntegerProperty("RECONNECT_TRIES"), this._props.getIntegerProperty("RECONNECT_RETRY_INTERVAL_IN_MSECS"), teNames);
				this._clientCollection.tempEndpointUpdate(epProps, i);
			} else {
				EpConfigProperties epProps;
				if (strQueues != null && strQueues.size() > 0) {
					if (this._props.getBooleanProperty("WANT_PROVISIONED_ENDPOINT")) {
						epProps = EpConfigProperties.CreateForEpProvision((List)strQueues.get(i % strQueues.size()), false, isAdding, (Constants.EndpointAccessType)this._props.getProperty("PE_ACCESS_TYPE"), this._props.getIntegerProperty("PE_MAXMSG_SIZE"), this._props.getIntegerProperty("PE_QUOTA_MB"), this._props.getStringProperty("PE_PERMISSION"), this._props.getIntegerProperty("PE_RESPECT_TTL"), this._props.getIntegerProperty("DISCARD_NOTIFY_SENDER"), this._props.getIntegerProperty("PE_MAX_MSG_REDELIVERY"), this._props.getBooleanProperty("AD_WANT_ACTIVE_FLOW_INDICATION"));
						this._clientCollection.endpointProvisioning(epProps, i);
					}

					numSubscriptions = (long)((List)strQueues.get(i % strQueues.size())).size();
					tempSelector = null;
					if (strSelectors != null) {
						tempSelector = (List)strSelectors.get(i % strSelectors.size());
					}

					epProps = EpConfigProperties.CreateForQueueEpAdd((List)strQueues.get(i % strQueues.size()), tempSelector, this._props.getBooleanProperty("WANT_NO_LOCAL"), transactedSessionName, this._props.getIntegerProperty("AD_TRANSACTION_SIZE"), this._props.getIntegerProperty("DISCARD_NOTIFY_SENDER"), this._props.getBooleanProperty("AD_WANT_ACTIVE_FLOW_INDICATION"), this._props.getBooleanProperty("WANT_MESSAGE_PRIORITY_ORDER_CHECKING"), this._props.getBooleanProperty("WANT_REPLAY"), this._props.getStringProperty("WANT_REPLAY_FROM_DATE"), this._props.getStringProperty("WANT_REPLAY_FROM_MSG_ID"), this._props.getIntegerProperty("RECONNECT_TRIES"), this._props.getIntegerProperty("RECONNECT_RETRY_INTERVAL_IN_MSECS"), this._props.getBooleanProperty("WANT_PROVISIONED_ENDPOINT"), (Constants.EndpointAccessType)this._props.getProperty("PE_ACCESS_TYPE"));
					Iterator var25;
					String queue;
					if (!this._props.getBooleanProperty("WANT_SUB_FIRST")) {
						this._clientCollection.queueUpdate(epProps, i);
						if (strTopics != null && !strTopics.isEmpty()) {
							var25 = ((List)strQueues.get(i % strQueues.size())).iterator();

							while(var25.hasNext()) {
								queue = (String)var25.next();
								this._clientCollection.mapTopics(queue, (List)strTopics.get(i % strTopics.size()), true, i);
							}
						}
					} else {
						if (strTopics != null && !strTopics.isEmpty()) {
							var25 = ((List)strQueues.get(i % strQueues.size())).iterator();

							while(var25.hasNext()) {
								queue = (String)var25.next();
								this._clientCollection.mapTopics(queue, (List)strTopics.get(i % strTopics.size()), true, i);
							}
						}

						this._clientCollection.queueUpdate(epProps, i);
					}
				} else if (strTopics != null && !strTopics.isEmpty() && strDtes != null && !strDtes.isEmpty()) {
					if (this._props.getBooleanProperty("WANT_PROVISIONED_ENDPOINT")) {
						epProps = EpConfigProperties.CreateForEpProvision((List)strDtes.get(i % strDtes.size()), true, isAdding, (Constants.EndpointAccessType)this._props.getProperty("PE_ACCESS_TYPE"), this._props.getIntegerProperty("PE_MAXMSG_SIZE"), this._props.getIntegerProperty("PE_QUOTA_MB"), this._props.getStringProperty("PE_PERMISSION"), this._props.getIntegerProperty("PE_RESPECT_TTL"), this._props.getIntegerProperty("DISCARD_NOTIFY_SENDER"), this._props.getIntegerProperty("PE_MAX_MSG_REDELIVERY"), this._props.getBooleanProperty("AD_WANT_ACTIVE_FLOW_INDICATION"));
						this._clientCollection.endpointProvisioning(epProps, i);
					}

					numSubscriptions = (long)((List)strTopics.get(i % strTopics.size())).size();
					tempSelector = null;
					if (strSelectors != null) {
						tempSelector = (List)strSelectors.get(i % strSelectors.size());
					}

					epProps = EpConfigProperties.CreateForTopicEpAdd((List)strDtes.get(i % strDtes.size()), (List)strTopics.get(i % strTopics.size()), tempSelector, this._props.getBooleanProperty("WANT_NO_LOCAL"), transactedSessionName, this._props.getIntegerProperty("AD_TRANSACTION_SIZE"), this._props.getIntegerProperty("DISCARD_NOTIFY_SENDER"), this._props.getBooleanProperty("WANT_REPLAY"), this._props.getStringProperty("WANT_REPLAY_FROM_DATE"), this._props.getStringProperty("WANT_REPLAY_FROM_MSG_ID"), this._props.getIntegerProperty("RECONNECT_TRIES"), this._props.getIntegerProperty("RECONNECT_RETRY_INTERVAL_IN_MSECS"));
					this._clientCollection.topicUpdate(epProps, i);
				} else if (strTopics != null && !strTopics.isEmpty()) {
					numSubscriptions = (long)((List)strTopics.get(i % strTopics.size())).size();
					AbstractCacheLiveDataAction lda = AbstractCacheLiveDataAction.QUEUE;
					if (this._props.getProperty("CACHE_LIVE_DATA_ACTION") != null) {
						lda = (AbstractCacheLiveDataAction)this._props.getProperty("CACHE_LIVE_DATA_ACTION");
					}

					if (this._props.getProperty("CACHE_WANT_REQUESTS_ON_SUBSCRIBE") != null && this._props.getBooleanProperty("CACHE_WANT_REQUESTS_ON_SUBSCRIBE")) {
						boolean subscribe = true;
						boolean waitForConfirm = true;
						long minSeq = -1L;
						long maxSeq = -1L;
						this._clientCollection.cacheRequest((List)strTopics.get(i % strTopics.size()), subscribe, lda, waitForConfirm, minSeq, maxSeq, i);
					} else {
						epProps = EpConfigProperties.CreateForSubscriptionAdd((List)strTopics.get(i % strTopics.size()), this._props.getStringProperty("SUBSCRIPTION_CLIENT_NAME"), SubscriberDestinationsType.TOPIC);
						this._clientCollection.subscriptionUpdate(epProps, i);
					}
				} else if (strDtes != null && !strDtes.isEmpty() && (strTopics == null || strTopics.isEmpty())) {
					System.out.println("E: Must specify topics when binding to durable topic endpoints.");
				}
			}

			long stopTime = Timing.getClockValue();
			if (wantSubscriptionRateStats) {
				double timeDiffInUsec = (double)(stopTime - startTime) / Timing.microSecDivisor();
				double subRate = 0.0;
				if (timeDiffInUsec > 0.0) {
					subRate = (double)numSubscriptions * 1000000.0 / timeDiffInUsec;
				}

				System.out.println(String.format("%s: Added %d subscriptions, time = %.2f us, rate = %.2f subscriptions/sec%n", this._clientCollection.getClientName(i), numSubscriptions, timeDiffInUsec, subRate));
			}
		}

	}

	private static void dumpProperties(RuntimeProperties rp) {
		if (rp.getBooleanProperty("WANT_VERBOSE")) {
			System.out.println("> Dumping configuration:");
			System.out.println(rp.toString());
		}

	}

	private String generateNamingInfo() {
		StringBuilder sb = new StringBuilder();
		sb.append("Client naming used:");
		sb.append("\n\tlogging ID   = ");
		if (!this._props.getStringProperty("CLIENT_USERNAME").equals("") || !this._props.getProperty("AUTHENTICATION_SCHEME").equals(GenericAuthenticationScheme.CLIENT_CERTIFICATE) && !this._props.getProperty("AUTHENTICATION_SCHEME").equals(GenericAuthenticationScheme.GSS_KRB)) {
			sb.append(ClientFactory.generateClientIdStr(this._props, 1));
		} else {
			sb.append("router generated.");
		}

		sb.append("\n\tusername     = " + ClientFactory.generateClientUsername(this._props, 1));
		sb.append("\n\tvpn          = " + this._props.getStringProperty("CLIENT_VPN"));
		sb.append("\n\tclient names = ");
		if (this._props.getStringProperty("CLIENT_NAME_PREFIX").equals("")) {
			sb.append("sdk generated.");
		} else {
			sb.append(ClientFactory.generateClientName(this._props, 1));
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

	private void shutdownHook() {
		System.out.println("> Running sdkperf shutdown...");
		if (this._clientCollection != null && this._clientCollection.isPublishing()) {
			try {
				this._clientCollection.stopPublishing();
			} catch (Exception var20) {
				Trace.error("Failed to stop publishing.", var20);
				var20.printStackTrace();
			}
		}

		if (this._clientCollection != null && this._clientCollection.isCacheRequesting()) {
			try {
				this._clientCollection.stopCacheRequesting();
			} catch (Exception var19) {
				Trace.error("Failed to stop cache requesting.", var19);
				var19.printStackTrace();
			}
		}

		if (this._clientCollection != null && this._clientCollection.isQueueBrowsing()) {
			try {
				this._clientCollection.stopQueueBrowsing();
			} catch (Exception var18) {
				Trace.error("Failed to stop queue browsing.", var18);
				var18.printStackTrace();
			}
		}

		if (this._clientCollection != null && this._clientCollection.isBinding()) {
			try {
				this._clientCollection.stopBinding();
			} catch (Exception var17) {
				Trace.error("Failed to stop bind thread.", var17);
				var17.printStackTrace();
			}
		}

		if (this._clientCollection != null && this._clientCollection.isConnected()) {
			int numclients;
			if (this._props.getProperty("PUBLISH_END_DELAY_IN_SEC") != null) {
				numclients = this._props.getIntegerProperty("PUBLISH_END_DELAY_IN_SEC") * 1000;
				System.out.println(String.format("Pausing -ped time to allow clients to finish recv messages (%s ms)", numclients));

				try {
					Thread.sleep((long)numclients);
				} catch (InterruptedException var16) {
				}
			}

			if (this._props.getIntegerProperty("SUB_MSG_QUEUE_DEPTH") > 0) {
				try {
					this._clientCollection.stop();
					numclients = this._props.getIntegerProperty("PUBLISH_END_DELAY_IN_SEC") * 1000;
					System.out.println(String.format("Pausing -ped time to allow flows to stop (%s ms)", numclients));

					try {
						Thread.sleep((long)numclients);
					} catch (InterruptedException var14) {
					}

					this._clientCollection.clearKeptMsgs();
				} catch (Exception var15) {
					Trace.error("Failed to clear kept messages.", var15);
					var15.printStackTrace();
				}
			}

			numclients = this._props.getIntegerProperty("NUM_CLIENTS");
			if (this._props.getIntegerProperty("AD_TRANSACTION_SIZE") > 0) {
				for(int i = 0; i < numclients; ++i) {
					try {
						boolean wantRollback = this._props.getIntegerProperty("AD_TRANSACTION_ROLLBACK_INTERVAL") == 1;
						if (this._props.getBooleanProperty("AD_WANT_XA_TRANSACTION")) {
							this._clientCollection.endXaSession((String)this._clientTransactedSessionNames.get(i), (String)this._clientXids.get(i), 67108864, i);
							boolean onePhase = this._props.getBooleanProperty("XA_WANT_ONE_PHASE_COMMIT_TRANSACTION");
							if (!onePhase) {
								this._clientCollection.prepareXaSession((String)this._clientTransactedSessionNames.get(i), (String)this._clientXids.get(i), i);
							}

							if (wantRollback) {
								this._clientCollection.rollbackXaSession((String)this._clientTransactedSessionNames.get(i), (String)this._clientXids.get(i), i);
							} else {
								this._clientCollection.commitXaSession((String)this._clientTransactedSessionNames.get(i), (String)this._clientXids.get(i), onePhase, i);
							}
						} else {
							this._clientCollection.commitTransaction((String)this._clientTransactedSessionNames.get(i), wantRollback, i);
						}
					} catch (PubSubException var13) {
						Trace.error("Failed to commit transactions during cleanup.", var13);
						var13.printStackTrace();
					}
				}
			}

			try {
				this._clientCollection.closeAllTransactedSessions(-1);
				if (this._props.getBooleanProperty("AD_WANT_XA_TRANSACTION")) {
					this._clientCollection.closeAllXaSessions(-1);
				}
			} catch (Exception var12) {
				Trace.error("Failed to close transactions during cleanup.", var12);
				var12.printStackTrace();
			}

			if (this._props.getBooleanProperty("WANT_REMOVE_SUBSCRIBER") && !this._props.getBooleanProperty("WANT_QUEUE_BROWSER")) {
				try {
					List<List<String>> strQueues = (List)this._props.getProperty("SUB_QUEUE_LISTS");
					List<List<String>> strTopics = (List)this._props.getProperty("SUB_TOPIC_LISTS");
					List<List<String>> strDtes = (List)this._props.getProperty("SUB_DTE_LISTS");
					boolean isAdding = false;
					int i;
					if (this._props.getIntegerProperty("NUM_TEMP_QUEUE_ENDPOINTS") <= 0 && this._props.getIntegerProperty("NUM_TEMP_TOPIC_ENDPOINTS") <= 0) {
						EpConfigProperties epProps;
						EpConfigProperties epProps2;
						if (strQueues != null && strQueues.size() > 0) {
							for(i = 0; i < numclients; ++i) {
								if (strTopics != null && !strTopics.isEmpty()) {
									Iterator var25 = ((List)strQueues.get(i % strQueues.size())).iterator();

									while(var25.hasNext()) {
										String queue = (String)var25.next();
										this._clientCollection.mapTopics(queue, (List)strTopics.get(i % strTopics.size()), false, i);
									}
								}

								epProps = EpConfigProperties.CreateForEpRemove((List)strQueues.get(i % strQueues.size()));
								this._clientCollection.queueUpdate(epProps, i);
								if (this._props.getBooleanProperty("WANT_PROVISIONED_ENDPOINT")) {
									epProps2 = EpConfigProperties.CreateForEpProvision((List)strQueues.get(i % strQueues.size()), false, isAdding, (Constants.EndpointAccessType)this._props.getProperty("PE_ACCESS_TYPE"), this._props.getIntegerProperty("PE_MAXMSG_SIZE"), this._props.getIntegerProperty("PE_QUOTA_MB"), this._props.getStringProperty("PE_PERMISSION"), this._props.getIntegerProperty("PE_RESPECT_TTL"), this._props.getIntegerProperty("DISCARD_NOTIFY_SENDER"), this._props.getIntegerProperty("PE_MAX_MSG_REDELIVERY"), this._props.getBooleanProperty("AD_WANT_ACTIVE_FLOW_INDICATION"));
									this._clientCollection.endpointProvisioning(epProps2, i);
								}
							}
						} else if (strTopics != null && strTopics.size() > 0 && strDtes != null && strDtes.size() > 0) {
							for(i = 0; i < numclients; ++i) {
								epProps = EpConfigProperties.CreateForEpRemove((List)strDtes.get(i % strDtes.size()));
								epProps.setTopicUnsubscribe(true);
								this._clientCollection.topicUpdate(epProps, i);
								if (this._props.getBooleanProperty("WANT_PROVISIONED_ENDPOINT")) {
									epProps2 = EpConfigProperties.CreateForEpProvision((List)strDtes.get(i % strDtes.size()), true, isAdding, (Constants.EndpointAccessType)this._props.getProperty("PE_ACCESS_TYPE"), this._props.getIntegerProperty("PE_MAXMSG_SIZE"), this._props.getIntegerProperty("PE_QUOTA_MB"), this._props.getStringProperty("PE_PERMISSION"), this._props.getIntegerProperty("PE_RESPECT_TTL"), this._props.getIntegerProperty("DISCARD_NOTIFY_SENDER"), this._props.getIntegerProperty("PE_MAX_MSG_REDELIVERY"), this._props.getBooleanProperty("AD_WANT_ACTIVE_FLOW_INDICATION"));
									this._clientCollection.endpointProvisioning(epProps2, i);
								}
							}
						} else if (strTopics != null && !strTopics.isEmpty() && !this._props.getStringProperty("SUBSCRIPTION_CLIENT_NAME").equals("")) {
							for(i = 0; i < numclients; ++i) {
								epProps = EpConfigProperties.CreateForSubscriptionRemove((List)strTopics.get(i % strTopics.size()), this._props.getStringProperty("SUBSCRIPTION_CLIENT_NAME"), SubscriberDestinationsType.TOPIC);
								this._clientCollection.subscriptionUpdate(epProps, i);
							}
						} else if (strTopics != null && !strTopics.isEmpty() && this._props.getStringProperty("SUBSCRIPTION_CLIENT_NAME").length() == 0 && this._props.getIntegerProperty("NUM_TEMP_QUEUE_ENDPOINTS") <= 0 && this._props.getIntegerProperty("NUM_TEMP_TOPIC_ENDPOINTS") <= 0 && (strQueues == null || strQueues.size() == 0) && (strDtes == null || strDtes.size() == 0)) {
							for(i = 0; i < numclients; ++i) {
								epProps = EpConfigProperties.CreateForSubscriptionRemove((List)strTopics.get(i % strTopics.size()), "", SubscriberDestinationsType.TOPIC);
								this._clientCollection.subscriptionUpdate(epProps, i);
							}
						}
					} else {
						for(i = 0; i < numclients; ++i) {
							this._clientCollection.unbindAllTempEndpoints(i);
						}
					}
				} catch (Exception var21) {
					System.out.println("E: Cleanup error: subscription removal failed.");
					var21.printStackTrace();
				}
			}

			try {
				this._clientCollection.closeAllTransactedSessions(-1);
				if (this._props.getBooleanProperty("AD_WANT_XA_TRANSACTION")) {
					this._clientCollection.closeAllXaSessions(-1);
				}

				this._clientCollection.disconnect();
			} catch (Exception var11) {
				Trace.error("Error occurred during client disconnect", var11);
			}
		}

		try {
			if (this._clientCollection != null) {
				System.out.println(StatFormatHelper.getFmtEndStats(this._clientCollection, this._props, this._clientCollection.getCpuUsage()));
			}
		} catch (PubSubException var10) {
			Trace.error("Error printing stats", var10);
			var10.printStackTrace();
		}

		try {
			if (this._clientCollection != null) {
				this._clientCollection.destroy();
			}
		} catch (Exception var9) {
			Trace.error("Error calling the final destroy() on the clientcollection", var9);
		}

		MemoryUtils.printGcInformation();
		System.out.println("> Exiting");
	}

	private static enum ReturnCode {
		SUCCESS,
		FAILURE;

		private ReturnCode() {
		}
	}
}
