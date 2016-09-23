package com.solace;

import java.awt.Color;
import java.awt.LayoutManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Properties;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;

public class SDKPerfControl extends JPanel {

	/**
	 * @author David Wray, Solace Systems
	 *
	 */
	private static final long serialVersionUID = 1L;
	private boolean bEnabled = false;
	private boolean bFast = false;
	private boolean bPersistent = false;
	
	private String HighSpeedSendRate;
	private String LowSpeedSendRate;
	private String HighSpeedRcvRate;
	private String LowSpeedRcvRate;
	private int ProgressRate;
	
	private Process SDKPerfProcess;
	private boolean bProducer;
	private boolean bConsumer;
	private boolean bDebug=false;
	private String jarPath;

	private JProgressBar progressBar = new JProgressBar(0,100);
	JToggleButton tglbtnEnable;
	private Task task;
	private boolean bConnected = false;

	class Task extends SwingWorker<Void, Void> {

		/*
		 * Main task. Executed in background thread.
		 */
		@Override
		public Void doInBackground() {
			//Initialize progress property.
			int progress = 0;
			progressBar.setMaximum(101);
//			System.out.println("starting to update progress bar");
			// wait until connected
			while (!bConnected && SDKPerfProcess.isAlive()) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
			while (SDKPerfProcess.isAlive()) {
				setProgress(0);
				progressBar.setValue(0);
				while (progress < 101 && SDKPerfProcess.isAlive()) {
					//Sleep for one second.
					try {
						Thread.sleep(1000);
					} catch (InterruptedException ignore) {}

					progress += ProgressRate;

					//Make progress.
//					setProgress(progress);
					progressBar.setValue(progress);
				}
				progress = 0;
			}
			return null;
		}

		/*
		 * Executed in event dispatching thread
		 */
		@Override
		public void done() {
			//reset prgress bar
			progressBar.setValue(0);
			progressBar.setMaximum(0);
			progressBar.setString("");
			bEnabled = false;		
			tglbtnEnable.setSelected(false);
		}
	}
	
	public SDKPerfControl() {
		super();
		// get values from properties file
		Properties prop = new Properties();
		ClassLoader loader = Thread.currentThread().getContextClassLoader();           
		InputStream stream = loader.getResourceAsStream("config.properties");
		try {
			prop.load(stream);
			HighSpeedSendRate = prop.getProperty("HighSpeedSendRate");
			LowSpeedSendRate = prop.getProperty("LowSpeedSendRate");
			HighSpeedRcvRate = prop.getProperty("HighSpeedRcvRate");
			LowSpeedRcvRate = prop.getProperty("LowSpeedRcvRate");
			bDebug = Boolean.parseBoolean(prop.getProperty("debug"));
			stream.close();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			SDKPerfUIApp.infoBox(e1.toString(), "Error Starting SDKPerf Process");
		}
		
		String path = SDKPerfControl.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		try {
			jarPath = URLDecoder.decode(path, "UTF-8");
		} catch (UnsupportedEncodingException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		setLayout(null);		
		
		JToggleButton tglbtnPersistent = new JToggleButton("Persistent");
		tglbtnPersistent.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				String debug;
				if (tglbtnPersistent.getSelectedObjects() == null) {
					bPersistent = false;
				} else {
					bPersistent = true;
				}
			}
		});

		tglbtnPersistent.setBounds(66, 5, 86, 29);
		add(tglbtnPersistent);
		
		JToggleButton tglbtnFast = new JToggleButton("Fast");
		tglbtnFast.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				String debug;
				if (tglbtnFast.getSelectedObjects() == null) {
					bFast = false;
				} else {
					bFast = true;
				}
			}
		});
		tglbtnFast.setBounds(143, 5, 59, 29);
		add(tglbtnFast);
		
		tglbtnEnable = new JToggleButton("Enable");
		tglbtnEnable.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				String debug;
				if (tglbtnEnable.getSelectedObjects() == null) {
					//disabled stop instance of SKDPerf
					bEnabled = false;
					StopProcess();
					StopProgressBar();
					debug = "  disabled";
				} else {
					//enabled start instance of SDKPerf
					bEnabled = true;
					// shouldn't be able to get here with a process but still
					StopProcess();
					StartProcess();
					AnimateProgressBar();				
				}
			}
		});
		tglbtnEnable.setBounds(0, 5, 75, 29);
		add(tglbtnEnable);
		
		progressBar.setBounds(6, 29, 192, 20);
		progressBar.setValue(0);
		progressBar.setStringPainted(false);		
		add(progressBar);
	}

	public SDKPerfControl(LayoutManager layout) {
		super(layout);
	}

	public SDKPerfControl(boolean isDoubleBuffered) {
		super(isDoubleBuffered);
		// TODO Auto-generated constructor stub
	}

	public SDKPerfControl(LayoutManager layout, boolean isDoubleBuffered) {
		super(layout, isDoubleBuffered);
		// TODO Auto-generated constructor stub
	}

	public void StopProcess() {
		if (SDKPerfProcess != null) {
			SDKPerfProcess.destroyForcibly();
			try {
				SDKPerfProcess.waitFor();
				SDKPerfProcess = null;
				bConnected = false;
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				SDKPerfUIApp.infoBox(e.toString(), "Error Stopping SDKPerf Process");
			}	
		}
	}
	
	protected void StartProcess() {
		//build args
		//common: shell, router, user@vpn
		ArrayList<String> Args  = new ArrayList<String>();
		Args.add("java");
		Args.add("-cp");
		Args.add(jarPath);
		Args.add("-Xms512m");
		Args.add("-Xmx1024m");
		Args.add("com.solacesystems.pubsub.sdkperf.SDKPerf_java");
		Args.add("-cip");
		Args.add(SDKPerfUIApp.getRouterAddress().getText());
		Args.add("-cu");
		Args.add(SDKPerfUIApp.getUser().getText()+"@"+SDKPerfUIApp.getVPN().getText());
		if (SDKPerfUIApp.getPassword().getPassword().length > 0) {
			Args.add("-cp");
			Args.add(new String(SDKPerfUIApp.getPassword().getPassword()));
		}
		
		if (bProducer){
			if (SDKPerfUIApp.isTopic()) {
				Args.add("-ptl");
			} else {
				Args.add("-pql");
			}				
			Args.add(SDKPerfUIApp.getMessageDestination().getText());
			Args.add("-mn");
			Args.add("1000000");
			Args.add("-msa");
			Args.add("10");
			Args.add("-mr");
			if (bFast) {
				Args.add(HighSpeedSendRate);
			} else {
				Args.add(LowSpeedSendRate);
				
			}
		}
		else{
			if (SDKPerfUIApp.isTopic()) {
				Args.add("-stl");
			} else {
				Args.add("-sql");
			}				
			Args.add(SDKPerfUIApp.getMessageDestination().getText());
			Args.add("-oc");
			if (!bFast) {
				Args.add("-sd");
				// calculate delay
				int msgPerSec = Integer.parseInt(LowSpeedRcvRate);
				int delay = 1000/msgPerSec;
				Args.add(Integer.toString(delay));
			}
		}
		if (bPersistent) {
			Args.add("-mt");
			Args.add("persistent");
		}
		
		if (bDebug) {
			SDKPerfUIApp.infoBox(Args.toString(), "DEBUG");		
		}
		try {
			//Apparently using new String [0] is actually more efficient, I still don't like it.
			ProcessBuilder pb = new ProcessBuilder(Args.toArray(new String[Args.size()]));
			
//			File log = new File("/Users/davidwray/Documents/Solace/Training/sol-sdkperf-7.1.2.33/sdkperf_java.log");
			pb.redirectErrorStream(true);
//			pb.redirectOutput(Redirect.appendTo(log));
			
			// set connected property for progress bar animation
			bConnected = false;

			SDKPerfProcess =  pb.start();
			Method connectedMethod = this.getClass().getMethod("setConnected");
			if (bProducer) {
				SDKPerfUIApp.setProducerTextStream(SDKPerfProcess.getInputStream(), connectedMethod, this);
			} else {
				SDKPerfUIApp.setConsumerTextStream(SDKPerfProcess.getInputStream(), connectedMethod, this);				
			}
		} catch (Exception e) {
			SDKPerfUIApp.infoBox(e.toString(), "Error Starting SDKPerf Process");
		}
	}

	public void setConnected() {
		bConnected=true;
	}

	public void setProducer(boolean b) {
		bProducer = b;
		bConsumer = !b;	
	}
	
	private void StopProgressBar() {
		task.cancel(true);
	}

	private void AnimateProgressBar() {	
		if (bFast) {
			ProgressRate = 10;
		} else {
			ProgressRate = 1;
		}
		task = new Task();
	    task.execute();
	}
}
