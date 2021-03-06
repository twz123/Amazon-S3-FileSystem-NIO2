package com.upplication.s3fs;

import static com.upplication.s3fs.S3UnitTestBase.S3_GLOBAL_URI;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.Before;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder;
import com.upplication.s3fs.util.CopyDirVisitor;
import com.upplication.s3fs.util.EnvironmentBuilder;
import org.junit.Test;

public class FilesOperationsIT {

	private static final URI uriEurope = URI.create("s3://s3-eu-west-1.amazonaws.com/");
	private static final String bucket = EnvironmentBuilder.getBucket();
    private static final URI uriGlobal = EnvironmentBuilder.getS3URI(S3_GLOBAL_URI);

	private FileSystem fileSystemAmazon;

    @Before
	public void setup() throws IOException {
        System.clearProperty(S3FileSystemProvider.AMAZON_S3_FACTORY_CLASS);
		fileSystemAmazon = build();
	}

	private static FileSystem build() throws IOException {
		try {
			FileSystems.getFileSystem(uriGlobal).close();
			return createNewFileSystem();
		} catch (FileSystemNotFoundException e) {
			return createNewFileSystem();
		}
	}

	private static FileSystem createNewFileSystem() throws IOException {
		return FileSystems.newFileSystem(uriGlobal, EnvironmentBuilder.getRealEnv());
	}

    @Test
	public void buildEnv() {
		FileSystem fileSystem = FileSystems.getFileSystem(uriGlobal);
		assertSame(fileSystemAmazon, fileSystem);
	}

    @Test
	public void buildEnvAnotherURIReturnDifferent() throws IOException {
		FileSystem fileSystem = FileSystems.newFileSystem(uriEurope, null);
		assertNotSame(fileSystemAmazon, fileSystem);
	}

    @Test
	public void notExistsDir() {
		Path dir = fileSystemAmazon.getPath(bucket, UUID.randomUUID().toString() + "/");
		assertTrue(!Files.exists(dir));
	}
    @Test
	public void notExistsFile() {

		Path file = fileSystemAmazon.getPath(bucket, UUID.randomUUID().toString());
		assertTrue(!Files.exists(file));
	}
    @Test
	public void existsFile() throws IOException {

		Path file = fileSystemAmazon.getPath(bucket, UUID.randomUUID().toString());

		EnumSet<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		Files.newByteChannel(file, options).close();

		assertTrue(Files.exists(file));
	}

    @Test
    public void existsFileWithSpace() throws IOException {
        Path file = fileSystemAmazon.getPath(bucket, UUID.randomUUID().toString(), "space folder");

        Files.createDirectories(file);

        for (Path p : Files.newDirectoryStream(file.getParent())) {
            assertTrue(Files.exists(p));
        }
    }

    @Test
	public void createEmptyDirTest() throws IOException {

		Path dir = createEmptyDir();

		assertTrue(Files.exists(dir));
		assertTrue(Files.isDirectory(dir));
	}
    @Test
	public void createEmptyFileTest() throws IOException {

		Path file = createEmptyFile();

		assertTrue(Files.exists(file));
		assertTrue(Files.isRegularFile(file));
	}
    @Test
	public void createTempFile() throws IOException {

		Path dir = createEmptyDir();

		Path file = Files.createTempFile(dir, "file", "temp");

		assertTrue(Files.exists(file));
	}
    @Test
	public void createTempDir() throws IOException {

		Path dir = createEmptyDir();

		Path dir2 = Files.createTempDirectory(dir, "dir-temp");

		assertTrue(Files.exists(dir2));
	}
    @Test
	public void deleteFile() throws IOException {
		Path file = createEmptyFile();
		Files.delete(file);

		Files.notExists(file);
	}
    @Test
	public void deleteDir() throws IOException {
		Path dir = createEmptyDir();
		Files.delete(dir);

		Files.notExists(dir);
	}
    @Test
	public void copyDir() throws IOException {
		Path dir = uploadDir();
		assertTrue(Files.exists(dir.resolve("assets1/")));
		assertTrue(Files.exists(dir.resolve("assets1/").resolve("index.html")));
		assertTrue(Files.exists(dir.resolve("assets1/").resolve("img").resolve("Penguins.jpg")));
		assertTrue(Files.exists(dir.resolve("assets1/").resolve("js").resolve("main.js")));
	}
    @Test
	public void directoryStreamBaseBucketFindDirectoryTest() throws IOException {
		Path bucketPath = fileSystemAmazon.getPath(bucket);
		String name = "01" + UUID.randomUUID().toString();
		final Path fileToFind = Files.createDirectory(bucketPath.resolve(name));

		try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(bucketPath)) {
			boolean find = false;
			for (Path path : dirStream) {
				// only first level
				assertEquals(bucketPath, path.getParent());
				if (path.equals(fileToFind)) {
					find = true;
					break;
				}
			}
			assertTrue(find);
		}
	}
    @Test
	public void directoryStreamBaseBucketFindFileTest() throws IOException {
		Path bucketPath = fileSystemAmazon.getPath(bucket);
		String name = "00" + UUID.randomUUID().toString();
		final Path fileToFind = Files.createFile(bucketPath.resolve(name));

		try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(bucketPath)) {
			boolean find = false;
			for (Path path : dirStream) {
				// check parent at first level
				assertEquals(bucketPath, path.getParent());
				if (path.equals(fileToFind)) {
					find = true;
					break;
				}
			}
			assertTrue(find);
		}
	}
    @Test
	public void directoryStreamFirstDirTest() throws IOException {
		Path dir = uploadDir();

		try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dir)) {
			int number = 0;
			for (Path path : dirStream) {
				number++;
				// solo recorre ficheros del primer nivel
				assertEquals(dir, path.getParent());
				assertEquals("assets1", path.getFileName().toString());
			}

			assertEquals(1, number);
		}
	}
    @Test
	public void virtualDirectoryStreamTest() throws IOException {

		String folder = UUID.randomUUID().toString();

		String file1 = folder + "/file.html";
		String file2 = folder + "/file2.html";

		Path dir = fileSystemAmazon.getPath(bucket, folder);

		S3Path s3Path = (S3Path) dir;
		// subimos un fichero sin sus paths
		ObjectMetadata metadata = new ObjectMetadata();
		metadata.setContentLength(0);
		s3Path.getFileSystem().getClient().putObject(s3Path.getFileStore().name(), file1, new ByteArrayInputStream(new byte[0]), metadata);
		// subimos otro fichero sin sus paths
		ObjectMetadata metadata2 = new ObjectMetadata();
		metadata.setContentLength(0);
		s3Path.getFileSystem().getClient().putObject(s3Path.getFileStore().name(), file2, new ByteArrayInputStream(new byte[0]), metadata2);

		try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dir)) {
			int number = 0;
			boolean file1Find = false;
			boolean file2Find = false;
			for (Path path : dirStream) {
				number++;
				// solo recorre ficheros del primer nivel
				assertEquals(dir, path.getParent());
				switch (path.getFileName().toString()) {
				case "file.html":
					file1Find = true;
					break;
				case "file2.html":
					file2Find = true;
					break;
				default:
					break;
				}

			}
			assertTrue(file1Find);
			assertTrue(file2Find);
			assertEquals(2, number);
		}
	}
    @Test
	public void virtualDirectoryStreamWithVirtualSubFolderTest() throws IOException {
		String folder = UUID.randomUUID().toString();

		String subfoler = folder + "/subfolder/file.html";
		String file2 = folder + "/file2.html";

		Path dir = fileSystemAmazon.getPath(bucket, folder);

		S3Path s3Path = (S3Path) dir;
		// subimos un fichero sin sus paths
		ObjectMetadata metadata = new ObjectMetadata();
		metadata.setContentLength(0);
		s3Path.getFileSystem().getClient().putObject(s3Path.getFileStore().name(), subfoler, new ByteArrayInputStream(new byte[0]), metadata);
		// subimos otro fichero sin sus paths
		ObjectMetadata metadata2 = new ObjectMetadata();
		metadata.setContentLength(0);
		s3Path.getFileSystem().getClient().putObject(s3Path.getFileStore().name(), file2, new ByteArrayInputStream(new byte[0]), metadata2);

		try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dir)) {
			int number = 0;
			boolean subfolderFind = false;
			boolean file2Find = false;
			for (Path path : dirStream) {
				number++;
				// solo recorre ficheros del primer nivel
				assertEquals(dir, path.getParent());
				switch (path.getFileName().toString()) {
				case "subfolder":
					subfolderFind = true;
					break;
				case "file2.html":
					file2Find = true;
					break;
				default:
					break;
				}

			}
			assertTrue(subfolderFind);
			assertTrue(file2Find);
			assertEquals(2, number);
		}
	}
    @Test
	public void deleteFullDirTest() throws IOException {
		Path dir = uploadDir();
		Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                if (exc == null) {
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
                throw exc;
            }
        });
		assertTrue(!Files.exists(dir));
	}
    @Test
	public void copyUploadTest() throws IOException {
		final String content = "sample content";
		Path result = uploadSingleFile(content);

		assertTrue(Files.exists(result));
		assertArrayEquals(content.getBytes(), Files.readAllBytes(result));
	}
    @Test
	public void copyDownloadTest() throws IOException {
		Path result = uploadSingleFile(null);

		Path localResult = Files.createTempDirectory("temp-local-file");
		Path notExistLocalResult = localResult.resolve("result");

		Files.copy(result, notExistLocalResult);

		assertTrue(Files.exists(notExistLocalResult));

		assertArrayEquals(Files.readAllBytes(result), Files.readAllBytes(notExistLocalResult));
	}
    @Test
	public void createFileWithFolderAndNotExistsFolders() {

		String fileWithFolders = UUID.randomUUID().toString() + "/folder2/file.html";

		Path path = fileSystemAmazon.getPath(bucket, fileWithFolders.split("/"));

		S3Path s3Path = (S3Path) path;
		// subimos un fichero sin sus paths
		ObjectMetadata metadata = new ObjectMetadata();
		metadata.setContentLength(0);
		s3Path.getFileSystem().getClient().putObject(s3Path.getFileStore().name(), fileWithFolders, new ByteArrayInputStream(new byte[0]), metadata);

		assertTrue(Files.exists(path));
		// debe ser true:
		assertTrue(Files.exists(path.getParent()));
	}
    @Test
	public void amazonCopyDetectContentType() throws IOException {
		try (FileSystem linux = MemoryFileSystemBuilder.newLinux().build("linux")) {
			Path htmlFile = Files.write(linux.getPath("/index.html"), "<html><body>html file</body></html>".getBytes());

			Path result = fileSystemAmazon.getPath(bucket, UUID.randomUUID().toString() + htmlFile.getFileName().toString());
			Files.copy(htmlFile, result);

			S3Path resultS3 = (S3Path) result;
			ObjectMetadata metadata = resultS3.getFileSystem().getClient().getObjectMetadata(resultS3.getFileStore().name(), resultS3.getKey());
			assertEquals("text/html", metadata.getContentType());
		}
	}
    @Test
	public void amazonCopyNotDetectContentTypeSetDefault() throws IOException {
		final byte[] data = new byte[] { (byte) 0xe0, 0x4f, (byte) 0xd0, 0x20, (byte) 0xea, 0x3a, 0x69, 0x10, (byte) 0xa2, (byte) 0xd8, 0x08, 0x00, 0x2b, 0x30, 0x30, (byte) 0x9d };
		try (FileSystem linux = MemoryFileSystemBuilder.newLinux().build("linux")) {
			Path htmlFile = Files.write(linux.getPath("/index.adsadas"), data);

			Path result = fileSystemAmazon.getPath(bucket, UUID.randomUUID().toString() + htmlFile.getFileName().toString());
			Files.copy(htmlFile, result);

			S3Path resultS3 = (S3Path) result;
			ObjectMetadata metadata = resultS3.getFileSystem().getClient().getObjectMetadata(resultS3.getFileStore().name(), resultS3.getKey());
			assertEquals("application/octet-stream", metadata.getContentType());
		}
	}
    @Test
	public void amazonOutpuStreamDetectContentType() throws IOException {
		try (FileSystem linux = MemoryFileSystemBuilder.newLinux().build("linux")) {
			Path htmlFile = Files.write(linux.getPath("/index.html"), "<html><body>html file</body></html>".getBytes());

			Path result = fileSystemAmazon.getPath(bucket, UUID.randomUUID().toString() + htmlFile.getFileName().toString());

			try (OutputStream out = Files.newOutputStream(result)) {
				// copied from Files.write
				byte[] bytes = Files.readAllBytes(htmlFile);
				int len = bytes.length;
				int rem = len;
				while (rem > 0) {
					int n = Math.min(rem, 8192);
					out.write(bytes, (len - rem), n);
					rem -= n;
				}
			}

			S3Path resultS3 = (S3Path) result;
			ObjectMetadata metadata = resultS3.getFileSystem().getClient().getObjectMetadata(resultS3.getFileStore().name(), resultS3.getKey());
			assertEquals("text/html", metadata.getContentType());
		}
	}
    @Test
	public void readAttributesDirectory() throws IOException {
		Path dir;

		final String startPath = "0000example" + UUID.randomUUID().toString() + "/";
		try (FileSystem linux = MemoryFileSystemBuilder.newLinux().build("linux")) {
			Path dirDynamicLocale = Files.createDirectories(linux.getPath("/lib").resolve("angular-dynamic-locale"));
			Path assets = Files.createDirectories(linux.getPath("/lib").resolve("angular"));
			Files.createFile(assets.resolve("angular-locale_es-es.min.js"));
			Files.createFile(assets.resolve("angular.min.js"));
			Files.createDirectory(assets.resolve("locales"));
			Files.createFile(dirDynamicLocale.resolve("tmhDinamicLocale.min.js"));
			dir = fileSystemAmazon.getPath(bucket, startPath);
			Files.exists(assets);
			Files.walkFileTree(assets.getParent(), new CopyDirVisitor(assets.getParent().getParent(), dir));
		}

		//dir = fileSystemAmazon.getPath("/upp-sources", "DES", "skeleton");

		BasicFileAttributes fileAttributes = Files.readAttributes(dir.resolve("lib").resolve("angular"), BasicFileAttributes.class);
		assertNotNull(fileAttributes);
		assertEquals(true, fileAttributes.isDirectory());
		assertEquals(startPath + "lib/angular/", fileAttributes.fileKey());
	}
    @Test
	public void seekableCloseTwice() throws IOException {
		Path file = createEmptyFile();

		SeekableByteChannel seekableByteChannel = Files.newByteChannel(file);
		seekableByteChannel.close();
		seekableByteChannel.close();

		assertTrue(Files.exists(file));
	}

    @Test
    public void testBucketIsDirectory() throws IOException {

        Path path = fileSystemAmazon.getPath(bucket, "/");
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        System.out.printf("size    : %s\n", attrs.size());
        System.out.printf("create  : %s\n", attrs.creationTime());
        System.out.printf("access  : %s\n", attrs.lastAccessTime());
        System.out.printf("modified: %s\n", attrs.lastModifiedTime());
        System.out.printf("dir     : %s\n", Files.isDirectory(path));
        assertEquals(0, attrs.size());
        assertEquals(null, attrs.creationTime());
        assertEquals(null, attrs.lastAccessTime());
        assertEquals(null, attrs.lastModifiedTime());
        assertTrue(attrs.isDirectory());

    }




    private Path createEmptyDir() throws IOException {
		Path dir = fileSystemAmazon.getPath(bucket, UUID.randomUUID().toString() + "/");

		Files.createDirectory(dir);
		return dir;
	}

	private Path createEmptyFile() throws IOException {
		Path file = fileSystemAmazon.getPath(bucket, UUID.randomUUID().toString());

		Files.createFile(file);
		return file;
	}

	private Path uploadSingleFile(String content) throws IOException {

		try (FileSystem linux = MemoryFileSystemBuilder.newLinux().build("linux")) {

			if (content != null) {
				Files.write(linux.getPath("/index.html"), content.getBytes());
			} else {
				Files.createFile(linux.getPath("/index.html"));
			}

			Path result = fileSystemAmazon.getPath(bucket, UUID.randomUUID().toString());

			Files.copy(linux.getPath("/index.html"), result);
			return result;
		}
	}

	private Path uploadDir() throws IOException {
		try (FileSystem linux = MemoryFileSystemBuilder.newLinux().build("linux")) {

			Path assets = Files.createDirectories(linux.getPath("/upload/assets1"));
			Files.createFile(assets.resolve("index.html"));
			Path img = Files.createDirectory(assets.resolve("img"));
			Files.createFile(img.resolve("Penguins.jpg"));
			Files.createDirectory(assets.resolve("js"));
			Files.createFile(assets.resolve("js").resolve("main.js"));

			Path dir = fileSystemAmazon.getPath(bucket, "0000example" + UUID.randomUUID().toString() + "/");

			Files.exists(assets);

			Files.walkFileTree(assets.getParent(), new CopyDirVisitor(assets.getParent(), dir));
			return dir;
		}
	}
}
