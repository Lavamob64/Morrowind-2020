package io.mte.updater;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

public class FileHandler {

	public static final UnzipUtility unzipper = new UnzipUtility();

	private static FileHandler instance;
	private static List<File> tempFiles;

	private static class ReleaseFiles {

		/** This is where the latest release will be unpacked */
		protected static final File dir = new File(
				FilenameUtils.removeExtension(RemoteHandler.RELEASE_FILENAME));
		
		/**
		 * Compare remote release files with their local counterparts
		 * @return a list of release files newer then local versions
		 */
		public static ArrayList<File> compare() {

			ArrayList<File> updates = new ArrayList<File>();
			File[] releaseFiles = dir.listFiles();
			/*
			 *  Iterate through all release files located in the directory
			 *  we extracted our downloaded release
			 */
			for (int i = 0; i < releaseFiles.length; i++) {

				File releaseFile = releaseFiles[i];
				String name = releaseFile.getName();
				File localFile = new File(name);

				if (!localFile.exists()) {
					Logger.print(Logger.Level.VERBOSE, "Local file %s not found, going to update", name);
					updates.add(releaseFile);
					continue;
				}
				Logger.print(Logger.Level.DEBUG, "Comparing %s release to local version", name);
				try {
					if (!FileUtils.contentEquals(releaseFile, localFile))
						updates.add(releaseFile);
				}
				catch (IOException e) {
					Logger.print(Logger.Level.ERROR, e, "Unable to compare release file %s to local version", name);
					continue;
				}
			}
			return updates;
		}
	}

	// Create file instances here at runtime
	// if there is any problems we can terminate application
	private FileHandler() {

		Logger.debug("Initializing file handler instance");
		
		// Store all our temporary file references here
		tempFiles = new ArrayList<File>();
	}
	
	public static void init() {
		/*
		 *  Initialize only once per session
		 */
		if (instance == null)
			instance = new FileHandler();
		else
			Logger.warning("Trying to initialize FileHandler more then once");
	}
	public static FileHandler get() {
		return instance;
	}

	protected static void launchApplication() {
		/*
		 *	 Create a copy of this application as a temporary file
		 */
		try {
			Logger.debug("Creating a temporary copy of application");
			String newSelfPath = FilenameUtils.removeExtension(Main.appPath.toString()) + ".tmp";
			Path selfUpdater = Files.copy(Main.appPath, Paths.get(newSelfPath), StandardCopyOption.REPLACE_EXISTING);
			
			String name = selfUpdater.getFileName().toString();
			Execute.launch("program.name", name, name, new String[] 
					{ "--update-self", String.valueOf(Main.processId), Logger.getLevel().getArguments()[0] });

			// Exit gracefully so we don't have to be terminated
			Execute.exit(0, false);
		}
		catch (IOException e) {
			Logger.error("Unable to create a copy of this application", e);
			Execute.exit(1, false);
		}
	}
	
	void doUpdate(float tag, String localSHA, String remoteSHA) {
		
		// Don't show changes if local version file is not present
		if (localSHA != null && !localSHA.isEmpty()) {
			/*
			 *  Open the Github website with the compare arguments in URL
			 */
			URI compareURL = RemoteHandler.getGithubCompareLink(localSHA, remoteSHA, true, true);
			if (compareURL == null || !RemoteHandler.browseWebpage(compareURL)) {
				return;
			}
		}
		// Download latest release files
		Logger.print("\nDownloading release files...");
		if (!RemoteHandler.downloadLatestRelease(tag))
			return;

		// Extract the release files to a new directory
		Logger.print("Extracting release files...");
		if (!extractReleaseFiles())
			return;
		
		// Move files from the target directory
		Logger.print("Updating local MTE files...");
		updateLocalFiles();
		
		// Update the guide version file
		Logger.print("Updating mte version file...");

		try (PrintWriter writer = new PrintWriter(VersionFile.Type.MTE.getName())) {
			writer.print(tag + " " + remoteSHA);
			Logger.print("\nYou're all set, good luck on your adventures!");
			writer.close();
		} catch (FileNotFoundException e) {
			Logger.error("ERROR: Unable to find mte version file!", e);
			Execute.exit(1, true);
		}
	}

	void updateLocalFiles() {
		
		Logger.verbose("Preparing to update release files...");
		ArrayList<File> releaseFiles = ReleaseFiles.compare();
		
		if (!releaseFiles.isEmpty()) {
			for (Iterator<File> iter = releaseFiles.iterator(); iter.hasNext(); ) {

				File updateFile = iter.next();

			    if (updateFile == null || !updateFile.exists()) {
			    	FileNotFoundException e = new FileNotFoundException();
			    	Logger.print(Logger.Level.ERROR, e, "Unable to find release file %s!", updateFile.getName());
			    	continue;
			    }
			    else {
			    	Path from = updateFile.toPath();
			    	Path to = Paths.get(updateFile.getName());

			    	Logger.print(Logger.Level.DEBUG, "Updating local file %s", updateFile.getName());
			    	Logger.print(Logger.Level.DEBUG, "Destination path: %s", to.toString());

			    	try {
						Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);

					} catch (IOException e) {
						Logger.print(Logger.Level.ERROR, e, "Unable to overwrite local release file %s !", to.getFileName().toString());
						continue;
					}
			    }
			}
		}
	}

	boolean extractReleaseFiles() {

		try {
			unzipper.unzip(RemoteHandler.RELEASE_FILENAME, ReleaseFiles.dir.getName());
			registerTempFile(ReleaseFiles.dir);
			return true;
		} catch (IOException e) {
			Logger.error("Unable to extract the GH repo file!", e);
			return false;
		}
	}

	/**
	 * Clean up all temporary files created in the update process
	 */
	static void updaterCleanup() {
		
		Logger.verbose("Recycling residual temporary files");
		String fileEntryName = "unknown";
		try {
			ListIterator<File> tempFileItr = tempFiles.listIterator();
			while (tempFileItr.hasNext()) {
				
				File fileEntry = tempFileItr.next();
				fileEntryName = fileEntry.getName();
				Logger.print(Logger.Level.DEBUG, "Recycling entry: %s", fileEntryName);
				/*
				 *  Make sure the file exists before we attempt to delete it
				 *  If it's a directory use AC-IO to delete the directory recursively
				 */
				if (fileEntry.exists()) {
					if (fileEntry.isDirectory())
						FileUtils.deleteDirectory(fileEntry);
					else
						// this might not work every time though
						fileEntry.deleteOnExit();
				}
			}
		} catch (SecurityException | IOException e) {
			Logger.print(Logger.Level.ERROR, e, "Unable to delete temporary file %s !", fileEntryName);
		}
		/*
		 *  Time to delete the temporary updater jar file
		 *  Do this only if we are in the appropriate run mode
		 */
		if (Main.isSelfUpdating())
			updateSelf();
	}
	
	/**
	 *  Run the final stage of the updater process.
	 *  The application will create an un-installer batch script and then run it.<br>
	 *  The script will delete the jvm application file as well as itself.
	 *  If we ran the {@link #updaterCleanup()} method before, this should leave 
	 *  our root folder completely clean of all temporary files.
	 */
	private static void updateSelf() {
		
		File uninstaller = new File("MTE-Updater-uninstall.bat");
		try {
			uninstaller.createNewFile();
		} catch (IOException e) {
			Logger.error("Unable to create uninstaller file", e);
			Execute.exit(1, false);
		}
		String[] batchLines = 
		{
				"@echo off",
				"set \"process=" + System.getProperty("program.name") + "\"",
				"set \"logfile=" + Logger.LogFile.NAME + "\"",
				"echo Running uninstaller script >> %logfile%",
				":uninstall",
				"timeout /t 1 /nobreak > nul",
				"2>nul ren %process% %process% && goto next || goto uninstall",
				":next",
				"if exist %process% (",
				"	echo Recycling temporary application file >> %logfile%",
				"	del %process%",
				") else ( echo [ERROR] Unable to delete application file %process% >> %logfile% )",
				"if exist %~n0%~x0 (",
				"	echo Recycling uninstaller script >> %logfile%",
				"	del %~n0%~x0",
				") else ( echo [ERROR] Unable to delete uninstaller script %~n0%~x0 >> %logfile% )"
		};
		
		try (PrintWriter writer = new PrintWriter(uninstaller.getName())) {
			
			for (int i = 0; i <= batchLines.length - 1; i++) {
				writer.println(batchLines[i]);
			}
			writer.close();
		} 
		catch (FileNotFoundException e) {
			Logger.error("ERROR: Unable to locate uninstaller script!", e);
			return;
		}
		
		Logger.debug("Launching uninstaller from JVM");
		Execute.start(uninstaller.getName(), false, false);
	}

	/**
	 * Any files added here will be deleted before terminating application
	 * @param tmpFile
	 */
	void registerTempFile(File tmpFile) {

		if (tmpFile.exists()) {
			Logger.print(Logger.Level.DEBUG, "Registering temporary file %s", tmpFile.getName());
			tempFiles.add(tmpFile);
		}
		else 
			Logger.print(Logger.Level.WARNING, "Trying to register a non-existing "
					+ "temporary file %s", tmpFile.getName());
	}

	/**
	 * Here we are using URL openStream method to create the input stream. Then we
	 * are using a file output stream to read data from the input stream and write
	 * to the file.
	 *
	 * @param url
	 * @param file
	 * @throws IOException
	 */
	boolean downloadUsingStream(URL url, String file) throws IOException {

		Logger.print(Logger.Level.DEBUG, "Downloading file %s from %s", file, url.toString());
		BufferedInputStream bis = new BufferedInputStream(url.openStream());
		FileOutputStream fis = new FileOutputStream(file);
		byte[] buffer = new byte[1024];
		int count = 0;
		while ((count = bis.read(buffer, 0, 1024)) != -1) {
			fis.write(buffer, 0, count);
		}

		fis.close();
		bis.close();

		File dlFile = new File(file);
		if (!dlFile.exists()) {
			Logger.print(Logger.Level.ERROR, "Unable to find downloaded file %s", dlFile.getName());
			return false;
		}
		return true;
	}

	/**
	 * Read from a text file and return the compiled string
	 *
	 * @param filename Name of the file to read from the root directory
	 * @return Content of the text file or {@code null} if an error occurred
	 */
	 static String readFile(String filename) {

		try (FileInputStream inputStream = new FileInputStream(filename)) {
			return IOUtils.toString(inputStream, "UTF-8");
		} catch (IOException e) {
			Logger.print(Logger.Level.ERROR, e, "Unable to read file %s", filename);
			return null;
		}
	}
}
