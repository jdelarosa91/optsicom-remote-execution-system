package es.optsicom.res.ssh.launcher.remote;

import java.awt.HeadlessException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;





import javax.swing.JOptionPane;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.IOConsoleOutputStream;
import org.eclipse.ui.console.MessageConsole;

import com.jcraft.jsch.*;

import es.optsicom.res.client.RESClientPlugin;
import es.optsicom.res.client.launcher.remote.IRemoteExecution;
import es.optsicom.res.client.util.ProjectDependenciesResolver;
import es.optsicom.res.server.OptsicomRemoteExecutor;
import es.optsicom.res.ssh.session.UserSessionInfo;


public class SSHRemoteExecution implements IRemoteExecution {
	
	private static final String FOLDERNAME="optsicom-res";
	String name="SSH";
	private boolean connected;
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
	private static final String resultFile="resultFile.txt";
	@Override
	public IStatus run(IProgressMonitor monitor) {	
		
		SubMonitor subMonitor = SubMonitor.convert(monitor);
		subMonitor.beginTask("Launching", 5);
		Session session=this.connect();
		subMonitor.subTask("Connecting to server");
		if (session!=null){
			subMonitor.subTask("Creating project folders");
			String opsticomFolder="/home/"+this.user+"/"+this.FOLDERNAME;
			this.executeCommand(session, "mkdir "+opsticomFolder);
			
					
			DateFormat hourFormat = new SimpleDateFormat("HH-mm");
			DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
			Date date = new Date();
			
			String serverProjectFolder= this.project.getElementName()+"_"+dateFormat.format(date)+
					"_"+hourFormat.format(date);
			
			this.serverProjectPath=opsticomFolder+"/"+serverProjectFolder;
			this.executeCommand(session, "mkdir "+serverProjectPath);
			
			//Enviamos el proeycto	
			subMonitor.subTask("Sending project");
			this.send(session);
			
			this.executeCommand(session, "find  -maxdepth 1 -type f  > "+this.serverProjectPath+"/initalDirectories.txt");
			
			subMonitor.subTask("Executing project");
			//Buscamos los archivos java los almacenamos y los compilamos guardando la dirección
			String expressionGetJavaSource="find "+serverProjectPath+" -name \"*.java\" > "+serverProjectPath+"/javafiles.txt";
			executeCommand(session, expressionGetJavaSource);
			
			
			String expressionCompileJava="javac @"+serverProjectPath+"/javafiles.txt";
			executeCommand(session, expressionCompileJava);
			
			
			
			String mainClassPath=getMainClassPath(session, serverProjectPath);
			
			mainClassPath=mainClassPath.replaceAll(mainClass+".java", "");
			executeProject(session, mainClassPath);
			
			this.executeCommand(session, "find  -maxdepth 1 -type f  > "+this.serverProjectPath+"/finalDirectories.txt");
			this.executeCommand(session, "diff "+this.serverProjectPath+"/initalDirectories.txt  "+this.serverProjectPath+"/finalDirectories.txt | grep -v \"^---\" | grep -v \"^[0-9c0-9]\" > "+this.serverProjectPath+"/outputFiles.txt");
			boolean isOutput=this.sendOutputFiles(session);
			this.openConsole(session, isOutput);
		}
		else{
			RESClientPlugin.log("Authentication failed");
		}
		session.disconnect();
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

	public void setDependenciesResolver(ProjectDependenciesResolver dependenciesResolver) {
		this.resolver = dependenciesResolver;
	}

	@Override
	public String getZipName() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void send(String zipName, OptsicomRemoteExecutor executor,
			SubMonitor monitor) throws IOException {
	
		
	}

	private void send (Session session){
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		String workSpaceRoot = ws.getRoot().getLocation().toOSString();
		StringBuffer executionResultsPath = new StringBuffer();
		executionResultsPath.append(workSpaceRoot);
		executionResultsPath.append(File.separator);
		executionResultsPath.append(this.project.getElementName());
		
		String expressionSCP="scp -r "+executionResultsPath+" "+user+"@"+host+": "+serverProjectPath;
		executeCommand(session, expressionSCP);
		
		
		//Enviamos los archivos adicionales	(creando las carpetas necesarias)	
		if(userSelectedResources != null) { 
			for(Object o : userSelectedResources) {
				IResource resource = (IResource) o;
				String absoluteResourcePath= resource.getLocation().toOSString();
				String relativeResourcePath= absoluteResourcePath.replaceAll(workSpaceRoot, "");
				
				if(relativeResourcePath.lastIndexOf('/')!=-1){
					relativeResourcePath=relativeResourcePath.substring(0, relativeResourcePath.lastIndexOf('/'));
				}
				String mkdirRelativePath="mkdir -p "+serverProjectPath+relativeResourcePath;
				executeCommand(session, mkdirRelativePath);
				String expressionSCPresource="scp -r "+absoluteResourcePath+" "+user+"@"+host+": "+serverProjectPath+relativeResourcePath;
				executeCommand(session, expressionSCPresource);
				
			}
		}
	}
	@Override
	public void openConsole(OptsicomRemoteExecutor executor, String idjob) {
		
		
	}
	
	public void openConsole(Session session, boolean isOutput) {
		ChannelExec channelExec;
		try {
			MessageConsole miconsola;
			ConsolePlugin plugin;
			IConsoleManager cm;
			final IOConsoleOutputStream cos;
			miconsola = new MessageConsole("Consola Optsicom RES", null);
			miconsola.activate();

			plugin = ConsolePlugin.getDefault();
			cm = plugin.getConsoleManager();
			cm.addConsoles(new IConsole[]{miconsola});
			
			cos = miconsola.newOutputStream();
			channelExec = (ChannelExec)session.openChannel("exec");	
			InputStream in = channelExec.getInputStream();
			channelExec.setCommand("cat "+serverProjectPath+"/"+this.resultFile);
			channelExec.connect();
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			String line; 
			while(true){
	               if(channelExec.isClosed()){ 
	            	   	while((line=reader.readLine())!= null){
		   					cos.write(line+"\n");
		   					cos.flush();
		               	}
		   	        	cos.write("\nFinished\n");
	   					cos.flush();
	   					break;
	               }
	               try{Thread.sleep(500);}catch(Exception ee){}
	            }
			if (isOutput){
				cos.write("There are some output files in : "+serverProjectPath+" \n");
					cos.flush();
			}
			cos.close();
			channelExec.disconnect();

		} catch (JSchException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}     
		
	}

	@Override
	public void getResultingFile(OptsicomRemoteExecutor executor, String idjob) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void getZipResultingFile(OptsicomRemoteExecutor executor,
			String idjob) {
		// TODO Auto-generated method stub
		
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

	//mgarcia: Optiscom Res evolution
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
			this.port=Integer.parseInt(port);
	    } catch(NumberFormatException e) { 
	        this.port=-1;
	    }				
	}

	@Override
	public void setUser(String user) {
		this.user=user;
	}
	
	private Session connect(){
		JSch jSSH = new JSch();
        Session session;
		try {
			session = jSSH.getSession(this.user, this.host, this.port);
			UserInfo userInfo = new UserSessionInfo(this.password, null);
	        session.setUserInfo(userInfo);        
	        session.setPassword(this.password);
	        session.connect();
	        return  session;
		} catch (JSchException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
        
	}
	
	private BufferedReader executeCommand(Session session, String command){
		ChannelExec channelExec;
		try {
			channelExec = (ChannelExec)session.openChannel("exec");
			
			InputStream in = channelExec.getInputStream();
			channelExec.setCommand(command);
			channelExec.connect();
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			 while(true){
	               if(channelExec.isClosed()){  
	            	   break;
	               }
	               try{Thread.sleep(500);}catch(Exception ee){}
	            }
			channelExec.disconnect();
	        return reader;
		} catch (JSchException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}     
    }
	
	private String getMainClassPath(Session session,String serverProjectPath){
		ChannelExec channelExec;
		try {
			channelExec = (ChannelExec)session.openChannel("exec");
			
			InputStream in = channelExec.getInputStream();
			channelExec.setCommand("grep "+this.mainClass+".java "+serverProjectPath+"/javafiles.txt");
			channelExec.connect();
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			String linea = null;
			 while(true){
	               if(channelExec.isClosed()){  
		   	           while((linea=reader.readLine())!= null){
		   	        	   break;
		               };
	            	   break;
	               }
	               try{Thread.sleep(200);}catch(Exception ee){}
	            }
			channelExec.disconnect();
	        return linea;
		} catch (JSchException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}  
	}
	
	private void executeProject(Session session, String mainClassPath){
		ChannelExec channelExec;
		try {
			channelExec = (ChannelExec)session.openChannel("exec");
			
			final InputStream in = channelExec.getInputStream();
			
			//Pasamos los argumentos a string para ejecutarlos.
			String args="";
			for(String arg : this.programArgs) {
				args+=arg+" ";
			}
			channelExec.setCommand( "java -cp "+mainClassPath+" "+mainClass+" "+args);
			
			channelExec.connect();
			
			//Escribimos la información en un fichero.
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			String line = null;
	        try {
	        	this.executeCommand(session, "touch "+serverProjectPath+"/"+resultFile);
	        	//Si la ejecución a finalizado escribimos en la consola y rompemos bucle, sino, se realiza una espera.
	            while(true){
	            	try{Thread.sleep(1000);}catch(Exception ee){}
		            if(channelExec.isClosed()){  
		   	           while((line=reader.readLine())!= null){
		   					this.executeCommand(session, "echo "+line+" >> "+serverProjectPath+"/"+resultFile);
		               }
	            	   break;
		            }
	               
	            }
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			channelExec.disconnect();
			
			
			
	       
		} catch (JSchException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();

		}  
	}

	@Override
	public boolean validateExecution() {
		Session session = this.connect();
		return session!=null;
	}
	
	private boolean sendOutputFiles(Session session){
		boolean isOutputs=false;
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		String workSpaceRoot = ws.getRoot().getLocation().toOSString();
		StringBuffer executionResultsPath = new StringBuffer();
		executionResultsPath.append(workSpaceRoot);
		executionResultsPath.append(File.separator);
		executionResultsPath.append(this.project.getElementName());
		String expressionSCPresource="grep \"\" "+this.serverProjectPath+"/outputFiles.txt";
		ChannelExec channelExec;
		try {
			channelExec = (ChannelExec)session.openChannel("exec");
			
			final InputStream in = channelExec.getInputStream();
			channelExec.setCommand(expressionSCPresource);
			
			channelExec.connect();

			BufferedReader bf=new BufferedReader(new InputStreamReader(in));
			String line;
			try {
				while ((line=bf.readLine())!=null){
					isOutputs=true;
					String path=line.substring(2, line.length());
					this.executeCommand(session, "scp -r "+user+"@"+host+": "+path+" "+executionResultsPath);
					this.executeCommand(session, "rm -rf "+path);
				}
			}
			catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		catch (Exception e) {
			// TODO Auto-generated catch block
		}
		return isOutputs;
	}
}