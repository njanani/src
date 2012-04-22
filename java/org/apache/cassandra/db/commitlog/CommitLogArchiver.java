package org.apache.cassandra.db.commitlog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;

import org.apache.cassandra.concurrent.JMXEnabledThreadPoolExecutor;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.WrappedRunnable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

public class CommitLogArchiver
{
    private static final Logger logger = LoggerFactory.getLogger(CommitLogArchiver.class);
    public final Map<String, Future<?>> archivePending = new ConcurrentHashMap<String, Future<?>>();
    public final ExecutorService executor = new JMXEnabledThreadPoolExecutor("commitlog_archiver");
    private final String archiveCommand;
    private final String restoreCommand;
    private final String restoreDirectories;
    public final long restorePointInTime;

    public CommitLogArchiver()
    {
        Properties commitlog_commands = new Properties();
        InputStream stream = null;
        try
        {
            stream = getClass().getClassLoader().getResourceAsStream("commitlog_archiving.properties");

            if (stream == null)
            {
                logger.debug("No commitlog_archiving properties found; archive + pitr will be disabled");
                archiveCommand = null;
                restoreCommand = null;
                restoreDirectories = null;
                restorePointInTime = Long.MAX_VALUE;
            }
            else
            {
                commitlog_commands.load(stream);
                archiveCommand = commitlog_commands.getProperty("archive_command");
                restoreCommand = commitlog_commands.getProperty("restore_command");
                restoreDirectories = commitlog_commands.getProperty("restore_directories");
                String targetTime = commitlog_commands.getProperty("restore_point_in_time");
                try
                {
                    restorePointInTime = Strings.isNullOrEmpty(targetTime) ? Long.MAX_VALUE : new SimpleDateFormat("yyyy:MM:dd HH:mm:ss").parse(targetTime).getTime();
                }
                catch (ParseException e)
                {
                    throw new RuntimeException("Unable to parse restore target time", e);
                }
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to load commitlog_archiving.properties", e);
        }
        finally
        {
            FileUtils.closeQuietly(stream);
        }
    }

    public void maybeArchive(final String path, final String name)
    {
        if (Strings.isNullOrEmpty(archiveCommand))
            return;

        archivePending.put(name, executor.submit(new WrappedRunnable()
        {
            protected void runMayThrow() throws IOException
            {
                String command = archiveCommand.replace("%name", name);
                command = command.replace("%path", path);
                exec(command);
            }
        }));
    }

    public boolean maybeWaitForArchiving(String name)
    {
        Future<?> f = archivePending.remove(name);
        if (f == null)
            return true; // archiving disabled

        try
        {
            f.get();
        }
        catch (InterruptedException e)
        {
            throw new AssertionError(e);
        }
        catch (ExecutionException e)
        {
            if (e.getCause() instanceof IOException)
            {
                logger.info("Looks like the archiving of file {} failed earlier, cassandra is going to ignore this segment for now.", name);
                return false;
            }
            throw new RuntimeException(e);
        }

        return true;
    }

    public void maybeRestoreArchive() throws IOException
    {
        if (Strings.isNullOrEmpty(restoreDirectories))
            return;

        for (String dir : restoreDirectories.split(","))
        {
            File[] files = new File(dir).listFiles();
            for (File fromFile : files)
            {
                File toFile = new File(DatabaseDescriptor.getCommitLogLocation(),
                                       CommitLogSegment.FILENAME_PREFIX +
                                       System.nanoTime() +
                                       CommitLogSegment.FILENAME_EXTENSION);             
                String command = restoreCommand.replace("%from", fromFile.getPath());
                command = command.replace("%to", toFile.getPath());       
                exec(command);
            }
        }
    }

    private void exec(String command) throws IOException
    {
        ProcessBuilder pb = new ProcessBuilder(command.split(" "));
        pb.redirectErrorStream(true);
        FBUtilities.exec(pb);
    }
}
