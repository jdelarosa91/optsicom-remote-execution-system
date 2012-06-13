package es.optsicom.res.client.launcher.remote.delegate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import es.optsicom.res.client.launcher.remote.RemoteExecutionJob;

public class RemoteVersionedLaunchConfiguration {
	
	protected static final String EMPTY_STRING = "";
	protected static final Map<String,String> EMPTY_MAP = new HashMap<String,String>();
	
	
	@SuppressWarnings("rawtypes")
	public void launch(final ILaunchConfiguration configuration, final String mode, final ILaunch launch, final IProgressMonitor monitor,String host, String portRmi,
					String portDebug, String password, String mainClass, String[] vmArgs, String[] programArgs, List userSelectedResources, IJavaProject project) throws CoreException {

			final RemoteExecutionJob job = new RemoteExecutionJob();
			job.setHost(host);
			job.setPortRMI(portRmi);
			job.setPortDebug(portDebug);
			job.setPassword(password);
			job.setVmArgs(vmArgs);
			job.setProgramArgs(programArgs);
			job.setMainClass(mainClass);
			job.setMode(mode);
			job.setUserSelectedResources(userSelectedResources);
			job.setProject(project);

			job.addJobChangeListener(new JobChangeAdapter() {
				@Override
				public void done(IJobChangeEvent event) {
					final IStatus status = event.getResult();
					if(status.isOK()) {
						if (configuration != null && "debug".equals(mode)){
							DebugUITools.launch(configuration, mode);
						}
					} else {
						Display.getDefault().asyncExec(new Runnable() {
							@Override
							public void run() {
								MessageDialog.openError(new Shell(), "Error", status.getMessage());
							}
						});
					}
				}
			});
			job.schedule();
			
	}
	
}