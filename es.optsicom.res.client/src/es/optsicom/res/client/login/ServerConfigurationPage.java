/* ******************************************************************************
 * This file is part of Optsicom Remote Experiment System client
 * 
 * License:
 *   EPL: http://www.eclipse.org/legal/epl-v10.html
 *   See the LICENSE file in the project's top-level directory for details.
 *   
 * Contributors:
 *   Optsicom(http://www.optsicom.es), Sidelab (http://www.sidelab.es) 
 *   and others
 * **************************************************************************** */
package es.optsicom.res.client.login;

import java.lang.reflect.InvocationTargetException;
import java.rmi.Naming;
import java.util.StringTokenizer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import es.optsicom.res.client.RESClientPlugin;
import es.optsicom.res.server.OptsicomRemoteExecutor;
import es.optsicom.res.server.OptsicomRemoteServer;

public class ServerConfigurationPage extends WizardPage {

	private static final String SERVERS = "SERVERS";
	private Text txtPass;
	private Text txtHost;
	private Text txtPortRmi;
	private Text txtPortDebug;
	private Text txtVMarg;
	private Text txtPrgarg;
	private String mode;
	private boolean connectionValid = false;

	protected ServerConfigurationPage(String mode) {
		super("ServerConfiguration");
		setTitle("Server Configuration");
//		setDescription("Server Configuration");
		setMessage("Configure the connection in this page and press the Validate button.\nAdditional resources needed can be selected in next page");
		this.mode = mode;
	}

	@Override
	public void createControl(Composite parent) {
		setTitle("Server Connection Configuration");

		Composite contents = new Composite(parent, SWT.NONE | SWT.BORDER);
		GridLayout gly = new GridLayout(2, false);
		contents.setLayout(gly);
		contents.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
		
		Label comboLabel = new Label(contents, SWT.LEFT);
		comboLabel.setText("Choose a previously saved configuration:");
		Combo savedConnections = new Combo(contents, SWT.BORDER | SWT.READ_ONLY);
		GridData gd = new GridData();
		gd.horizontalAlignment = SWT.FILL;
		gd.grabExcessHorizontalSpace = true;
		savedConnections.setLayoutData(gd);
		
		Preferences prefs = new InstanceScope().getNode(RESClientPlugin.PLUGIN_ID);
		Preferences savedServers = prefs.node(SERVERS);
		String[] serverNames = new String[0];
		try {
			serverNames = savedServers.keys();
		} catch (BackingStoreException e) {
			RESClientPlugin.log(e);
		}
		
		savedConnections.setItems(serverNames);
		savedConnections.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				Combo c = (Combo) e.widget;
				int itemSelected = c.getSelectionIndex();
				Preferences prefs = new InstanceScope().getNode(RESClientPlugin.PLUGIN_ID);
				Preferences savedServers = prefs.node(SERVERS);
				String parameters = new String(savedServers.get(c.getItem(itemSelected), ""));
				StringTokenizer st = new StringTokenizer(parameters, ":");
				txtHost.setText(st.nextToken());
				txtPortRmi.setText(st.nextToken());
				
			}
		});
			 
		Group datosPersonales = new Group(contents, SWT.SHADOW_IN);
		GridData gridDataHV = new GridData();
		gridDataHV.horizontalAlignment = GridData.FILL;
		gridDataHV.horizontalSpan = 2;
		gridDataHV.grabExcessHorizontalSpace = true;
		datosPersonales.setLayoutData(gridDataHV);
		datosPersonales.setText("Authentication");
		gly = new GridLayout();
		gly.numColumns = 2;
		datosPersonales.setLayout(gly);
		datosPersonales.setSize(200, 200);
				
		Group datosConexion = new Group(contents, SWT.SHADOW_IN);
		gridDataHV = new GridData();
		gridDataHV.horizontalSpan = 2;
		gridDataHV.horizontalAlignment = GridData.FILL;
		gridDataHV.grabExcessHorizontalSpace = true;
		datosConexion.setLayoutData(gridDataHV);
		datosConexion.setText("Connection data");
		gly = new GridLayout();
		gly.numColumns = 2;
		datosConexion.setLayout(gly);
		datosConexion.setSize(200, 200);
		
		Group argumentos = new Group(contents, SWT.SHADOW_IN);
		gridDataHV = new GridData();
		gridDataHV.horizontalSpan = 2;
		gridDataHV.horizontalAlignment = GridData.FILL;
		gridDataHV.grabExcessHorizontalSpace = true;
		argumentos.setLayoutData(gridDataHV);
		argumentos.setText("Arguments");
		gly = new GridLayout(2, false);
		argumentos.setLayout(gly);
		argumentos.setSize(200, 150);
		
		Button validate = new Button(contents, SWT.PUSH);
		validate.setText("Validate connection");
		validate.addSelectionListener(new SelectionAdapter() {
			
			@Override
			public void widgetSelected(SelectionEvent event) {
				IRunnableWithProgress op = new IRunnableWithProgress() {
					@Override
					public void run(IProgressMonitor monitor) throws InvocationTargetException,
							InterruptedException {
						doValidate(monitor);
					}
				};
				
				try {
					getWizard().getContainer().run(false, false, op);
				} catch (Exception e) {
					RESClientPlugin.log(e);
				}
				
				// We refresh the wizard buttons so that Finish can have a chance to be activated
				getWizard().getContainer().updateButtons();
			}
			
		});
		gd = new GridData();
		gd.horizontalAlignment = SWT.LEFT;
		gd.horizontalSpan = 1;
		validate.setLayoutData(gd);
		
		Button saveSettings = new Button(contents, SWT.PUSH);
		saveSettings.setText("Save settings");
		saveSettings.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				InputDialog input = new InputDialog(getShell(), "Save settings", "Enter the connection name", "", null);
				int result = input.open();
				if(result == InputDialog.OK) {
					String configName = input.getValue();
					String parameters = txtHost.getText() + ":" + txtPortRmi.getText();
					Preferences prefs = new InstanceScope().getNode(RESClientPlugin.PLUGIN_ID);
					Preferences savedServers = prefs.node(SERVERS);
					savedServers.put(configName, parameters);
					try {
						savedServers.flush();
					} catch (BackingStoreException e1) {
						RESClientPlugin.log(e1);
						MessageDialog.openError(getShell(), "Saving settings", "Unable to store settings: " + e1.getMessage());
					}
				}
			}
		});
		gd = new GridData();
		gd.horizontalAlignment = SWT.LEFT;
		gd.grabExcessHorizontalSpace = true;
		saveSettings.setLayoutData(gd);
	 
		Label lblPass = new Label(datosPersonales, SWT.CENTER);
		lblPass.setText("Password:");
		txtPass = new Text(datosPersonales, SWT.BORDER | SWT.PASSWORD);
		gridDataHV = new GridData();
		gridDataHV.horizontalSpan = 1;
		gridDataHV.horizontalAlignment = GridData.FILL;
		gridDataHV.grabExcessHorizontalSpace = true;	 
		txtPass.setLayoutData(gridDataHV);
		
		Label lblHost = new Label(datosConexion, SWT.LEFT);
		lblHost.setText("Host:");
		txtHost = new Text(datosConexion, SWT.BORDER | SWT.SINGLE);
		gridDataHV = new GridData();
		gridDataHV.horizontalSpan = 1;
		gridDataHV.horizontalAlignment = GridData.FILL;
		gridDataHV.grabExcessHorizontalSpace = true;	 
		txtHost.setLayoutData(gridDataHV);
		
		Label lblPortRmi = new Label(datosConexion, SWT.LEFT);
		lblPortRmi.setText("Host RMI port:");
		txtPortRmi = new Text(datosConexion, SWT.BORDER | SWT.SINGLE);
		gridDataHV = new GridData();
		gridDataHV.horizontalSpan = 1;
		gridDataHV.horizontalAlignment = GridData.FILL;
		gridDataHV.grabExcessHorizontalSpace = true;
		txtPortRmi.setLayoutData(gridDataHV);
		
		Label lblPortDebug = new Label(datosConexion, SWT.LEFT);
		lblPortDebug.setText("VM Debug port:");
		txtPortDebug = new Text(datosConexion, SWT.BORDER | SWT.SINGLE);
		gridDataHV = new GridData();
		gridDataHV.horizontalSpan = 1;
		gridDataHV.horizontalAlignment = GridData.FILL;
		gridDataHV.grabExcessHorizontalSpace = true;
		txtPortDebug.setLayoutData(gridDataHV);
		if (!mode.equals("debug")){
			txtPortDebug.setEditable(false);
			txtPortDebug.setEnabled(false);
		}
		
		Label lblVMarg = new Label(argumentos, SWT.LEFT);
		lblVMarg.setText("VM arguments:");
		txtVMarg = new Text(argumentos, SWT.BORDER | SWT.SINGLE);
		gridDataHV = new GridData();
		gridDataHV.horizontalSpan = 1;
		gridDataHV.horizontalAlignment = GridData.FILL;
		gridDataHV.grabExcessHorizontalSpace = true;	 
		txtVMarg.setLayoutData(gridDataHV);
		
		Label lblPrgarg = new Label(argumentos, SWT.LEFT);
		lblPrgarg.setText("Program arguments:");
		txtPrgarg = new Text(argumentos, SWT.BORDER | SWT.SINGLE);
		gridDataHV = new GridData();
		gridDataHV.horizontalSpan = 1;
		gridDataHV.horizontalAlignment = GridData.FILL;
		gridDataHV.grabExcessHorizontalSpace = true;	 
		txtPrgarg.setLayoutData(gridDataHV);

		setControl(contents);
	}

	public String getPasswd() {
		return txtPass.getText();
	}

	public String getHost() {
		return txtHost.getText();
	}

	public String getPortRMI() {
		return txtPortRmi.getText();
	}

	public String getVMArgs() {
		return txtVMarg.getText();
	}

	public String getProgramArgs() {
		return txtPrgarg.getText();
	}

	public String getVMDebugPort() {
		return txtPortDebug.getText();
	}

	public boolean isConnectionValid() {
		return connectionValid;
	}

	private void doValidate(IProgressMonitor monitor) {
		try {
			connectionValid = false;
			monitor.beginTask("Validating connection", 2);
			OptsicomRemoteServer veex = (OptsicomRemoteServer) Naming.lookup("//"+txtHost.getText()+":"+txtPortRmi.getText()+"/optsicom");
			monitor.worked(1);
			if(veex != null) {
				OptsicomRemoteExecutor executor = veex.getExecutor();
				monitor.worked(1);
				if(executor != null) {
					connectionValid = true;
					setPageComplete(true);
					setErrorMessage(null);
					setMessage("Connection validated succesfully");
				} else {
					setErrorMessage("Optsicom server returned null");
					RESClientPlugin.log("OptsicomRemoteServer.getExecutor() returned null");
				}
			} else {
				setErrorMessage("Naming returned null");
				RESClientPlugin.log("Naming returned null");
			}
		} catch (Exception e) {
			RESClientPlugin.log(e);
			setErrorMessage(e.getMessage());
		}
	}

}