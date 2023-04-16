package pl.skidam.automodpack.utils;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.StaticVariables;
import pl.skidam.automodpack.config.Jsons;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Everything in this class should force do the thing without throwing any exceptions.
 */

public class CustomFileUtils {
    private static final long maxEmptyZipFolderSize = 168;

    public static void forceDelete(File file, boolean deleteOnExit) {
        if (file.exists()) {
            FileUtils.deleteQuietly(file);

            if (file.exists()) {
                try {
                    FileDeleteStrategy.FORCE.delete(file);
                } catch (IOException ignored) {
                }
            }


            if (file.exists() && file.length() > maxEmptyZipFolderSize) {
                if (file.toString().endsWith(".jar")) {
                    ZipIntoEmptyFolder(file);
                } else {
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(new byte[0]);
                    } catch (IOException ignored) {
                    }
                }
            }

            if (file.exists()) {
                try {
                    FileDeleteStrategy.FORCE.delete(file);
                } catch (IOException ignored) {
                }
            }

            if (deleteOnExit && file.exists()) {
                file.deleteOnExit();
            }
        }
    }

    public static void copyFile(File source, File destination) throws IOException {
        if (!destination.exists()) {
            if (!destination.getParentFile().exists()) destination.getParentFile().mkdirs();
            Files.createFile(destination.toPath());
        }
        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(destination)) {

             FileChannel sourceChannel = inputStream.getChannel();
             FileChannel destinationChannel = outputStream.getChannel();

            destinationChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
        }
    }

    public static void deleteEmptyFiles(File directory, boolean deleteSubDirsToo, List<Jsons.ModpackContentFields.ModpackContentItems> ignoreList) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (shouldIgnore(file, ignoreList)) {
//                System.out.println("Ignoring: " + file);
                continue;
            }

            if (file.isDirectory()) {

                if (file.getName().startsWith(".")) {
                    continue;
                }

                if (deleteSubDirsToo && isEmptyDirectory(file, ignoreList)) {
//                    System.out.println("Deleting empty dir: " + file);
                    CustomFileUtils.forceDelete(file, false);
                }

                else {
                    deleteEmptyFiles(file, deleteSubDirsToo, ignoreList);
                }

            } else if (file.length() == 0) {
//                System.out.println("Deleting empty file: " + file);
                CustomFileUtils.forceDelete(file, true);
            } else if (file.length() <= maxEmptyZipFolderSize) {
                deleteEmptyZipFolder(file);
            }
        }
    }

    private static boolean shouldIgnore(File file, List<Jsons.ModpackContentFields.ModpackContentItems> ignoreList) {
        return ignoreList.stream()
                .anyMatch(item -> file.getAbsolutePath().replace("\\", "/").endsWith(item.file));
    }

    private static boolean isEmptyDirectory(File directory, List<Jsons.ModpackContentFields.ModpackContentItems> ignoreList) {
        File[] files = directory.listFiles();

        if (files == null && directory.length() == 0) {
            return true;
        } else {
            for (File file : files) {
                if (!shouldIgnore(file, ignoreList)) {
                    return false;
                }
            }
        }
        
        return false;
    }

    public static void ZipIntoEmptyFolder(File zipFile) {
        File folderPath = new File(StaticVariables.automodpackDir + File.separator + "empty");
        folderPath.mkdirs();

        try {
            // Get a reference to the existing ZIP file
            ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(zipFile));

            // Create a new ZIP entry for the empty folder
            ZipEntry zipEntry = new ZipEntry(folderPath + File.separator);
            zipOutputStream.putNextEntry(zipEntry);

            // Close the ZIP output stream
            zipOutputStream.close();

            folderPath.delete();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void deleteEmptyZipFolder(File file) {
        if (!file.toString().endsWith(".jar")) {
            return;
        }

        if (JarUtilities.getModIdFromJar(file, true) != null) {
            return;
        }

        CustomFileUtils.forceDelete(file, true);
        System.out.println("Deleted empty zip folder: " + file);
    }

    public static String getHashFromStringOfHashes(String hashes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(hashes.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }


    public static String getHash(File file, String algorithm) throws Exception {
        if (!file.exists()) return null;

        MessageDigest md = MessageDigest.getInstance(algorithm);

        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }

        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }

        return sb.toString();
    }

    public static String getHashWithRetry(File file, String algorithm) throws NoSuchAlgorithmException {
        try {
            return getHash(file, algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw e;
        } catch (Exception e) {
            // ignore NullPointerException
        }

        File tempFile = new File(StaticVariables.automodpackDir + File.separator + file.getName() + ".tmp");
        try {
            CustomFileUtils.copyFile(file, tempFile);
            return getHash(tempFile, algorithm);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("AutoModpack - Cannot copy file for hashing: " + file.getAbsolutePath(), e);
        } finally {
            tempFile.delete();
        }
    }

    public static boolean compareFileHashes(File file1, File file2, String algorithm) throws Exception {
        if (!file1.exists() || !file1.exists()) return false;

        String hash1 = getHashWithRetry(file1, algorithm);
        String hash2 = getHashWithRetry(file2, algorithm);

        if (hash1 == null || hash2 == null) return false;

        return hash1.equals(hash2);
    }

    public static List<File> mapAllFiles(File directory, List<File> files) {
        File[] filesInDir = directory.listFiles();
        if (filesInDir == null) {
            return files;
        }

        for (File file : filesInDir) {
            if (file.isDirectory()) {
                mapAllFiles(file, files);
            } else {
                files.add(file);
            }
        }

        return files;
    }
}
