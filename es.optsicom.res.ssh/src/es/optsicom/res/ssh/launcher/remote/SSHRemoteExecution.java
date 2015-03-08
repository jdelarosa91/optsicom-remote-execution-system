package es.optsicom.res.ssh.launcher.remote;
import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.logging.Level;

import javax.swing.JOptionPane;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.IOConsoleOutputStream;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import com.jcraft.jsch.*;

import es.optsicom.res.client.RESClientPlugin;
import es.optsicom.res.client.launcher.remote.IRemoteExecution;
import es.optsicom.res.client.util.ProjectDependenciesResolver;
import es.optsicom.res.ssh.session.UserSessionInfo;

public class SSHRemoteExecution implements IRemoteExecution {
	// it is used for the exceptions.
	java.util.logging.Logger logger = java.util.logging.Logger
			.getLogger(SSHRemoteExecution.class.getName());

	private static final String FOLDERNAME = "optsicom-res";
	private static final String LOGFILE = "/log.txt";
	private static final String JAVAFILES="/javafiles.txt";
	private Session session;
	private String name = "SSH";
	private String host;
	private int port;
	private String portDebug;
	private String password;
	private String mainClass;
	private String[] vmArgs;
	private String[] programArgs;
	private String zipName;
	private String mode;
	private String user;
	private ProjectDependenciesResolver resolver;
	private List userSelectedResources;
	private IJavaProject project;
	private String serverProjectPath;
	private StringBuilder executionResultsPath;
	private static final String RESULTFILE = "RESULTFILE.txt";
	private boolean isOutput;

	public SSHRemoteExecution() {

	}

	@Override
	public IStatus run(IProgressMonitor monitor) {

		SubMonitor subMonitor = SubMonitor.convert(monitor);
		subMonitor.beginTask("Launching", 5);
		connect();
		subMonitor.subTask("Connecting to server");
		if (this.session != null) {
			subMonitor.subTask("Creating project folders");
			String opsticomFolder = "/home/" + this.user + "/"
					+ FOLDERNAME;
			this.executeCommand("mkdir " + opsticomFolder, false);

			DateFormat hourFormat = new SimpleDateFormat("HH-mm-ss");
			DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
			Date date = new Date();

			String serverProjectFolder = this.project.getElementName() + "_"
					+ dateFormat.format(date) + "_" + hourFormat.format(date);

			this.serverProjectPath = opsticomFolder + "/" + serverProjectFolder;
			this.executeCommand("mkdir " + serverProjectPath, false);

			// Enviamos el proeycto
			subMonitor.subTask("Sending project");
			
			send(subMonitor);

			executeCommand("echo name:" + this.project.getElementName() + ">>"
					+ serverProjectPath + LOGFILE, false);
			executeCommand("echo date:" + dateFormat.format(date) + "/"
					+ hourFormat.format(date) + ">>" + serverProjectPath
					+ LOGFILE, false);
			executeCommand("echo status:sent " + ">>" + serverProjectPath
					+ LOGFILE, false);

			subMonitor.subTask("Executing project");
			// Buscamos los archivos java los almacenamos y los compilamos
			// guardando la dirección
			String expressionGetJavaSource = "find " + serverProjectPath
					+ " -name \"*.java\" > " + serverProjectPath
					+ JAVAFILES;
			executeCommand(expressionGetJavaSource,false);

			String expressionCompileJava = "javac @" + serverProjectPath
					+ JAVAFILES;
			executeCommand(expressionCompileJava, false);
			executeCommand("sed -i 's/status:[a-z]*/status:compiled/' "
					+ serverProjectPath + LOGFILE, false);
			
			String mainClassPath = getMainClassPath(serverProjectPath);

			mainClassPath = mainClassPath.replaceAll(mainClass + ".java", "");
			executeCommand("echo mainclass:" + mainClassPath + mainClass + "  "
					+ ">>" + serverProjectPath + LOGFILE, false);
			String idjob = serverProjectFolder;

			ScopedPreferenceStore sps = (ScopedPreferenceStore) RESClientPlugin
					.getDefault().getPreferenceStore();
			sps.putValue(idjob, name + ":" + host + ":" + port + ":" + user
					+ ":" + password);
			executeCommand("sed -i 's/status:[a-z]*/status:executing/' "
					+ serverProjectPath + LOGFILE, false);

			// Creamos los ficheros necesarios antes de realizar el control de
			// los ficheros de salida.
			executeCommand("touch " + serverProjectPath + LOGFILE, false);
			executeCommand("touch " + serverProjectPath + "/" + RESULTFILE, false);
			executeCommand("touch " + serverProjectPath
					+ "/finalDirectories.txt", false);

			executeCommand("find " + this.serverProjectPath
					+ "  -maxdepth 1 -type f  > " + this.serverProjectPath
					+ "/initialDirectories.txt", false);
			executeProject(mainClassPath);
			executeCommand("find " + this.serverProjectPath
					+ "  -maxdepth 1 -type f  > " + this.serverProjectPath
					+ "/finalDirectories.txt", false);

			executeCommand("diff " + this.serverProjectPath
					+ "/initialDirectories.txt  " + this.serverProjectPath
					+ "/finalDirectories.txt"
					+ " | grep -v \"^---\" | grep -v \"^[0-9c0-9]\" " + "> "
					+ this.serverProjectPath + "/outputFiles.txt", false);
			executeCommand("echo output files: >> " + serverProjectPath
					+ LOGFILE, false);
			executeCommand("cat " + this.serverProjectPath + "/outputFiles.txt"
					+ " >> " + serverProjectPath + LOGFILE, false);

			// Obtenemos los resultados y los enviamos a la carpeta results.
			IWorkspace ws = ResourcesPlugin.getWorkspace();
			String workSpaceRoot = ws.getRoot().getLocation().toOSString();
			executionResultsPath = new StringBuilder();
			executionResultsPath.append(workSpaceRoot);
			executionResultsPath.append(File.separator);
			executionResultsPath.append(this.project.getElementName());
			File folder = new File(executionResultsPath + "/results");
			folder.mkdirs();

			subMonitor.subTask("Getting output files");
			this.isOutput = this.sendOutputFiles();
			executeCommand("sed -i 's/status:[a-z]*/status:finished/' "
					+ serverProjectPath + LOGFILE, false);
			subMonitor.subTask("Opening console");
			this.openConsole(idjob);
		} else {
			RESClientPlugin.log("Authentication failed");
		}
		this.session.disconnect();
		return new Status(IStatus.OK, RESClientPlugin.PLUGIN_ID, "");
	}

	public void setHost(String host) {
		this.host = host;
	}

	public void setPortDebug(String portDebug) {
		this.portDebug = portDebug;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void setMainClass(String mainClass) {
		this.mainClass = mainClass;
	}

	public void setVmArgs(String[] vmArgs) {
		this.vmArgs = vmArgs;
	}

	public void setProgramArgs(String[] programArgs) {
		this.programArgs = programArgs;
	}

	public void setDependenciesResolver(
			ProjectDependenciesResolver dependenciesResolver) {
		this.resolver = dependenciesResolver;
	}

	public void send(SubMonitor monitor) {
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		String workSpaceRoot = ws.getRoot().getLocation().toOSString();
		StringBuilder projectPath = new StringBuilder();
		projectPath.append(workSpaceRoot);
		projectPath.append(File.separator);
		projectPath.append(this.project.getElementName());
		
		Channel channel;
		try {
			channel = session.openChannel("sftp");
			channel.connect();
			ChannelSftp channelSftp= (ChannelSftp) channel;
			channelSftp.cd(serverProjectPath.toString());
			uploadDir(projectPath.toString(), serverProjectPath, channelSftp);
			
			
			
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		/*String expressionSCP = "scp -r -p -t " + projectPath;
		executeCommand(expressionSCP, true);
		// Enviamos los archivos adicionales (creando las carpetas necesarias)
		if (userSelectedResources != null) {
			for (Object o : userSelectedResources) {
				IResource resource = (IResource) o;
				String absoluteResourcePath = resource.getLocation()
						.toOSString();
				String relativeResourcePath = absoluteResourcePath.replaceAll(
						workSpaceRoot, "");

				if (relativeResourcePath.lastIndexOf('/') != -1) {
					relativeResourcePath = relativeResourcePath.substring(0,
							relativeResourcePath.lastIndexOf('/'));
				}
				String mkdirRelativePath = "mkdir -p " + serverProjectPath
						+ relativeResourcePath;
				executeCommand(mkdirRelativePath, false);
				String expressionSCPresource = "scp -r " + absoluteResourcePath
						+ " " + user + "@" + host + ": " + serverProjectPath
						+ relativeResourcePath;
				executeCommand(expressionSCPresource, false);
			}
		}*/
	}

	public void openConsole(String idjob) {
		try {
			MessageConsole miconsola;
			ConsolePlugin plugin;
			IConsoleManager cm;
			final IOConsoleOutputStream cos;
			miconsola = new MessageConsole("Consola Optsicom RES", null);
			miconsola.activate();
			plugin = ConsolePlugin.getDefault();
			cm = plugin.getConsoleManager();
			cm.addConsoles(new IConsole[] { miconsola });
			cos = miconsola.newOutputStream();

			Channel channel;
			try {
				channel = session.openChannel("sftp");
				channel.connect();
				ChannelSftp channelSftp= (ChannelSftp) channel;
				new File(executionResultsPath + "/results"  + "/" + RESULTFILE);
                channelSftp.get(serverProjectPath +"/" + RESULTFILE, executionResultsPath + "/results" + "/" + RESULTFILE); 
				//downloadDir(serverProjectPath + "/" + RESULTFILE , executionResultsPath + "/results", channelSftp);
				File localResultFile = new File(executionResultsPath + "/results/"
					+ RESULTFILE);
				FileReader fr = new FileReader(localResultFile);
				BufferedReader bf = new BufferedReader(fr);
				String line;
				while ((line = bf.readLine()) != null) {
					cos.write(line + "\n");
					cos.flush();
				}
				cos.write("Finished\n");
				cos.flush();
				bf.close();
			}
			catch (Exception e){
				
			}
			if (this.isOutput) {
				cos.write("There are some output files in : "
						+ executionResultsPath + "/results \n");
				cos.flush();
			}
			cos.close();
			// Eliminamos los archivos del servidor.
			/*executeCommand("rm -rf " + this.serverProjectPath
					+ "/initialDirectories.txt " + serverProjectPath
					+ "/finalDirectories.txt " + serverProjectPath
					+ JAVAFILES+" " + serverProjectPath + "/"
					+ this.project.getElementName(), false);*/

		} catch (IOException e) {
			String message = "Unexpected IOException in processing!";
			logger.log(Level.SEVERE, message, e);
		}

	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public void setZipName(String zipName) {
		this.zipName = zipName;
	}

	public void setResolved(ProjectDependenciesResolver resolver) {
		this.resolver = resolver;
	}

	// mgarcia: Optiscom Res evolution
	public void setUserSelectedResources(List userSelectedResources) {
		this.userSelectedResources = userSelectedResources;
	}

	public List getUserSelectedResources() {
		return userSelectedResources;
	}

	public void setProject(IJavaProject project) {
		this.project = project;
	}

	public IJavaProject getProject() {
		return project;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void setPort(String port) {
		try {
			this.port = Integer.parseInt(port);
		} catch (NumberFormatException e) {
			String message = "Unexpected NumberFormatException in processing!";
			logger.log(Level.SEVERE, message, e);
			this.port = -1;
		}
	}

	@Override
	public void setUser(String user) {
		this.user = user;
	}

	private void connect() {
		JSch jSSH = new JSch();
		try {
			this.session = jSSH.getSession(this.user, this.host, this.port);
			UserInfo userInfo = new UserSessionInfo(this.password, null);
			this.session.setUserInfo(userInfo);
			this.session.setPassword(this.password);
			this.session.connect();
			
		} catch (JSchException e) {
			String message = "Unexpected JSCH Exception in processing!";
			logger.log(Level.SEVERE, message, e);
		}

	}

	private String getMainClassPath(String serverProjectPath) {
		String[] shellOutputs = this.executeCommand("grep " + this.mainClass
				+ ".java " + serverProjectPath + JAVAFILES, false);

		if (shellOutputs != null) {
			return shellOutputs[0];
		}
		return null;

	}

	private void executeProject(String mainClassPath) {
		// Pasamos los argumentos a string para ejecutarlos.
		String args = "";
		for (String arg : this.programArgs) {
			args += arg + " ";
		}
		// Inicializamos el fichero por si contiene algo
		executeCommand("echo  > " + serverProjectPath + "/" + RESULTFILE, false);
		executeCommand("cd " + serverProjectPath
				+ " && java -cp " + mainClassPath + " " + mainClass + " "
				+ args + " >> " + serverProjectPath + "/" + RESULTFILE, false);
	}

	private boolean sendOutputFiles() {
		String[] outputShell = this.executeCommand("grep \"\" "
				+ this.serverProjectPath + "/outputFiles.txt", false);
		Channel channel;
		for (int i = 0; i < outputShell.length; i++) {
			isOutput = true;
			String path = outputShell[i].substring(2, outputShell[i].length());
			try {
				String fileName [] = path.split("/");
				channel = session.openChannel("sftp");
				channel.connect();
				ChannelSftp channelSftp= (ChannelSftp) channel;
				new File(executionResultsPath + "/results"  + "/" + fileName[fileName.length-1]);
	            channelSftp.get(path, executionResultsPath + "/results" + "/" + fileName[fileName.length-1]);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			 
			//downloadDir(serverProjectPath + "/" + RESULTFILE , executionResultsPath + "/results", channelSftp);
			/*this.executeCommand("scp -r " + user + "@" + host + ": " + path
					+ " " + executionResultsPath + "/results", false);*/
		}
		return isOutput;
	}

	private String[] executeCommand(String command, boolean scp) {
		ChannelExec channelExec;
		List<String> lines = null;
		if(!scp){
			try {
				channelExec = (ChannelExec) this.session.openChannel("exec");
				InputStream in = channelExec.getInputStream();
				channelExec.setCommand(command);
				
				
				channelExec.connect();
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(in));
				String line;
				lines = new ArrayList<String>();
				while (true) {
					if (channelExec.isClosed()) {
						while ((line = reader.readLine()) != null) {
							lines.add(line);
						}
						break;
					}
					try {
						Thread.sleep(50);
					} catch (Exception ee) {
						String message = "Unexpected Trhead sleep exception in processing!";
						logger.log(Level.SEVERE, message, ee);
					}
				}
				channelExec.disconnect();

			} catch (JSchException e) {
				String message = "Unexpected JSCHException in processing!";
				logger.log(Level.SEVERE, message, e);
			} catch (IOException e) {
				String message = "Unexpected IOException in processing!";
				logger.log(Level.SEVERE, message, e);
			}
			return (lines == null) ? null : lines.toArray(new String[lines.size()]);
		}
		else{
			boolean primeStamp=true;
			try {
				Channel channel = session.openChannel("exec");
				((ChannelExec) channel).setCommand(command);
				try {
					OutputStream out = channel.getOutputStream();
					InputStream in = channel.getInputStream();
					
					channel.connect();
					
					
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				
			} catch (JSchException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			return null;
		}
		
	}

	@Override
	public boolean validateExecution() {
		this.connect();
		return session.isConnected();
	}

	@Override
	public void getResultingFile(String idjob) {
		sendOutputFiles();
	}

	@Override
	public String getZipName() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getState(String idjob) {
		connect();
		String opsticomFolder = "/home/" + this.user + "/" + FOLDERNAME;
		String logPath = opsticomFolder + "/" + idjob;
		String[] output = executeCommand("grep 'status:[a-z]*' " + logPath
				+ LOGFILE, false);
		String status = "undetermined";
		if (output.length > 0) {
			status = output[0];
			String[] tokens = status.split(":");
			if (tokens.length > 1) {
				status = tokens[1];
			} else {

			}
		} else {

		}
		return status;
	}

	@Override
	public void getResultFromView(String workspace, String idjob) {
		JOptionPane.showMessageDialog(null, "1");
		connect();
		JOptionPane.showMessageDialog(null, "2");
		try {
			MessageConsole miconsola;
			ConsolePlugin plugin;
			IConsoleManager cm;
			final IOConsoleOutputStream cos;
			miconsola = new MessageConsole("Consola Optsicom RES", null);
			miconsola.activate();
			plugin = ConsolePlugin.getDefault();
			cm = plugin.getConsoleManager();
			cm.addConsoles(new IConsole[] { miconsola });
			cos = miconsola.newOutputStream();
			JOptionPane.showMessageDialog(null, "3");
			// Obtenemos el nombre del proyecto
			String opsticomFolder = "/home/" + this.user + "/"
					+ FOLDERNAME;
			String logPath = opsticomFolder + "/" + idjob;
			String[] output = executeCommand("grep 'name:[a-z]*' " + logPath
					+ LOGFILE, false);
			String nameExecution = "undetermined";
			if (output.length > 0) {
				nameExecution = output[0];
				String[] tokens = nameExecution.split(":");
				if (tokens.length > 1) {
					nameExecution = tokens[1];
				} 
				else {
					//In case of there is any error to split the saved execution.
				}
			} 
			else {
				//In case of not output
			}

			File folder = new File(workspace + "/" + nameExecution + "/results");
			folder.mkdirs();
			Channel channel=null;
			channel = session.openChannel("sftp");
			channel.connect();
			ChannelSftp channelSftp= (ChannelSftp) channel;
			
			serverProjectPath = "/home/" + this.user + "/" + FOLDERNAME
					+ "/" + idjob;
			// Enviamos los archivos de salida
			
			String[] outputShell = this.executeCommand("grep \"\" "
					+ serverProjectPath + "/outputFiles.txt",false);
			/*for (int i = 0; i < outputShell.length; i++) {
				isOutput = true;
				String path = outputShell[i].substring(2, outputShell[i].length());
				try {
					String fileName [] = path.split("/");
					new File(executionResultsPath + "/results"  + "/" + fileName[fileName.length-1]);
		            channelSftp.get(path, executionResultsPath + "/results" + "/" + fileName[fileName.length-1]);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}*/
			
			new File(executionResultsPath + "/results"  + "/" + RESULTFILE);
            channelSftp.get(serverProjectPath +"/" + RESULTFILE, workspace + "/" + nameExecution +  "/results" + "/" + RESULTFILE); 
			/*this.executeCommand("scp -r " + user + "@" + host + ": "
					+ serverProjectPath + "/" + RESULTFILE + " " + workspace
					+ "/" + nameExecution + "/results",false);*/
			File localResultFile = new File(workspace + "/" + nameExecution
					+ "/results/" + RESULTFILE);
			FileReader fr = new FileReader(localResultFile);
			BufferedReader bf = new BufferedReader(fr);
			String line;
			while ((line = bf.readLine()) != null) {
				cos.write(line + "\n");
				cos.flush();
			}
			cos.write("Finished\n");
			cos.flush();
			if (this.isOutput) {
				cos.write("There are some output files in : " + workspace + "/"
						+ nameExecution + "/results \n");
				cos.flush();
			}
			cos.close();
			bf.close();

			// Eliminamos los archivos del servidor.
			executeCommand("rm -rf " + this.serverProjectPath
					+ "/initialDirectories.txt " + serverProjectPath
					+ "/finalDirectories.txt " + serverProjectPath
					+ JAVAFILES+" " + serverProjectPath + "/"
					+ this.project.getElementName(),false);
		} catch (Exception e) {
			String message = "Unexpected IOException in processing!";
			logger.log(Level.SEVERE, message, e);
		}
	}

	public void uploadDir(String sourcePath, String destPath, ChannelSftp sftpChannel) throws SftpException { // With subfolders and all files.
	    // Create local folders if absent.
		String folderProject=null;
	    try {
	    	String sourcePath_aux[] = sourcePath.split("/");
	    	folderProject=sourcePath_aux[sourcePath_aux.length-1];
	    	
	    	sftpChannel.mkdir(folderProject);
		    sftpChannel.cd(folderProject);
		    lsFolderCopyInServer(new File (sourcePath), sftpChannel); // Separated because loops itself inside for subfolders.
	    } catch (Exception e) {
	        System.out.println("Error at : " + destPath);
	    }
	}

	private void lsFolderCopyInServer(File sourcePath, ChannelSftp sftpChannel) throws SftpException, FileNotFoundException { // List source (remote, sftp) directory and create a local copy of it - method for every single directory.
	    File []list= (sourcePath).listFiles(); // List source directory structure.
	    for (int i=0; i<list.length; i++) { // Iterate objects in the list to get file/folder names.
	    	if(list[i].isDirectory()){
	    		sftpChannel.mkdir(list[i].getName());
	    		sftpChannel.cd(list[i].getName());
	    		lsFolderCopyInServer(list[i], sftpChannel);
	    		sftpChannel.cd("..");
	    	}
	    	else{
	    		sftpChannel.put(new FileInputStream(list[i]), list[i].getName(), ChannelSftp.OVERWRITE);
	    	}
	    }
	}
	public void downloadDir(String sourcePath, String destPath, ChannelSftp sftpChannel) throws SftpException, FileNotFoundException { // With subfolders and all files.
		// Create local folders if absent.
	    try {
	        new File(destPath).mkdirs();
	        
	    } catch (Exception e) {
	        System.out.println("Error at : " + destPath);
	    }
	    sftpChannel.lcd(destPath);
        lsFolderCopy(sourcePath, destPath, sftpChannel); // Separated because loops itself inside for subfolders.
	    
	}

	private void lsFolderCopy(String sourcePath, String destPath,ChannelSftp sftpChannel) throws SftpException, FileNotFoundException { // List source (remote, sftp) directory and create a local copy of it - method for every single directory.
		Vector<ChannelSftp.LsEntry> list = sftpChannel.ls(sourcePath); // List source directory structure.
	    for (ChannelSftp.LsEntry oListItem : list) { // Iterate objects in the list to get file/folder names.
	        JOptionPane.showMessageDialog(null, oListItem.getFilename());
	    	if (!oListItem.getAttrs().isDir()) { // If it is a file (not a directory).
	            if (!(new File(destPath + "/" + oListItem.getFilename())).exists() || (oListItem.getAttrs().getMTime() > Long.valueOf(new File(destPath + "/" + oListItem.getFilename()).lastModified() / (long) 1000).intValue())) { // Download only if changed later.
	                new File(destPath + "/" + oListItem.getFilename());
	                JOptionPane.showMessageDialog(null, "Se crea file");
	                sftpChannel.get(sourcePath + "/" + oListItem.getFilename(), destPath + "/" + oListItem.getFilename()); // Grab file from source ([source filename], [destination filename]).
	            }
	        } else if (!".".equals(oListItem.getFilename()) || "..".equals(oListItem.getFilename())) {
	            new File(destPath + "/" + oListItem.getFilename()).mkdirs(); // Empty folder copy.
	            lsFolderCopy(sourcePath + "/" + oListItem.getFilename(), destPath + "/" + oListItem.getFilename(), sftpChannel); // Enter found folder on server to read its contents and create locally.
	        }
	    }
	}
}

