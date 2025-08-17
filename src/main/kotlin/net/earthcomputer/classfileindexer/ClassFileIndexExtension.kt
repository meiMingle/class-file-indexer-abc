package net.earthcomputer.classfileindexer

import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.*
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.KeyDescriptor
import net.earthcomputer.classfileindexer.config.CFIState
import net.earthcomputer.classfileindexer.libs.org.objectweb.asm.ClassReader
import java.io.DataInput
import java.io.DataOutput

class ClassFileIndexExtension :
    FileBasedIndexExtension<String, Map<BinaryIndexKey, Map<String, Int>>>() {
    override fun getName() = INDEX_ID

    override fun getIndexer() = DataIndexer<String, Map<BinaryIndexKey, Map<String, Int>>, FileContent> { content ->
        val bytes = content.content
        val cv = IndexerClassVisitor()
        ClassReader(bytes).accept(cv, ClassReader.SKIP_FRAMES)
        @Suppress("USELESS_CAST") // kotlin compiler bug
        cv.index as Map<String, Map<BinaryIndexKey, Map<String, Int>>>
    }

    override fun getKeyDescriptor() = object : KeyDescriptor<String> {
        override fun getHashCode(value: String): Int = value.hashCode()

        override fun isEqual(val1: String, val2: String) = val1 == val2

        override fun save(out: DataOutput, value: String) {
            writeString(out, value)
        }

        override fun read(input: DataInput) = readString(input)
    }

    override fun getValueExternalizer() = object : DataExternalizer<Map<BinaryIndexKey, Map<String, Int>>> {
        override fun save(out: DataOutput, value: Map<BinaryIndexKey, Map<String, Int>>) {
            ProgressManager.checkCanceled()
            DataInputOutputUtil.writeINT(out, value.size)
            for ((key, counts) in value) {
                key.write(out, ::writeString)
                DataInputOutputUtil.writeINT(out, counts.size)
                for ((location, count) in counts) {
                    writeString(out, location)
                    DataInputOutputUtil.writeINT(out, count)
                }
            }
        }

        override fun read(input: DataInput): Map<BinaryIndexKey, Map<String, Int>> {
            ProgressManager.checkCanceled()
            val result = SmartMap<BinaryIndexKey, MutableMap<String, Int>>()
            repeat(DataInputOutputUtil.readINT(input)) {
                val key = BinaryIndexKey.read(input, ::readString)
                val counts = SmartMap<String, Int>()
                repeat(DataInputOutputUtil.readINT(input)) {
                    val location = readString(input)
                    val count = DataInputOutputUtil.readINT(input)
                    counts[location] = count
                }
                result[key] = counts
            }
            return result
        }
    }

    override fun getVersion() = 4

    override fun getInputFilter() = object : DefaultFileTypeSpecificInputFilter(JavaClassFileType.INSTANCE) {

        val libraryPathCache : HashMap<String, Boolean> = HashMap()

        override fun acceptInput(file: VirtualFile): Boolean {
            if (!CFIState.getInstance().enabled) {
                return false
            }

            val index = file.path.lastIndexOf("!") // Find within Jar
            if (index == -1) {
                return CFIState.getInstance().useBlacklist
            }

            val libraryPath = file.path.substring(0, index)
            if (!libraryPathCache.computeIfAbsent(libraryPath) { path ->
                CFIState.getInstance().canIncludeLibrary(path)
            }) {
                return CFIState.getInstance().useBlacklistLibrary
            }

            val classPath = file.path.substring(index + 2).removeSuffix("." + file.extension)
            return CFIState.getInstance().canIncludeClazz(classPath)
        }
    }

    override fun dependsOnFileContent() = true

    companion object {
//        private val LOGGER = Logger.getInstance(ClassFileIndexExtension::class.java)
        val INDEX_ID = ID.create<String, Map<BinaryIndexKey, Map<String, Int>>>("classfileindexer.index")
//        private const val ENUMERATOR_INITIAL_SIZE = 1024 * 4
    }

    // TODO: when all this becomes stable API...
    // TODO: could also take advantage of custom map implementations to call ProgressManager.checkCanceled() more often
//    private val enumeratorPath: Path = IndexInfrastructure.getIndexRootDir(INDEX_ID).resolve("classfileindexer.constpool")
//    private var enumerator = createEnumerator()
//
//    private fun createEnumerator(): PersistentStringEnumerator {
//        @Suppress("UnstableApiUsage")
//        return PersistentStringEnumerator(enumeratorPath, ENUMERATOR_INITIAL_SIZE, true, StorageLockContext(true))
//    }
//
//    private fun recreateEnumerator() {
//        IOUtil.closeSafe(LOGGER, enumerator)
//        IOUtil.deleteAllFilesStartingWith(enumeratorPath.toFile())
//        enumerator = createEnumerator()
//    }

    private fun readString(input: DataInput): String {
        return input.readUTF().intern()
//        return enumerator.valueOf(DataInputOutputUtil.readINT(input))?.intern()
//            ?: throw IOException("Invalid enumerated string")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeString(output: DataOutput, value: String) {
        output.writeUTF(value)
//        try {
//            DataInputOutputUtil.writeINT(output, enumerator.enumerate(value))
//        } catch (e: Throwable) {
//            recreateEnumerator()
//            FileBasedIndex.getInstance().requestRebuild(INDEX_ID, e)
//            throw e
//        }
    }

//    @Suppress("UnstableApiUsage")
//    override fun createIndexImplementation(
//        extension: FileBasedIndexExtension<String, Map<BinaryIndexKey, Map<String, Int>>>,
//        indexStorageLayout: VfsAwareIndexStorageLayout<String, Map<BinaryIndexKey, Map<String, Int>>>
//    ) = object : VfsAwareMapReduceIndex<String, Map<BinaryIndexKey, Map<String, Int>>>(extension, indexStorageLayout, null) {
//        override fun doClear() {
//            super.doClear()
//            recreateEnumerator()
//        }
//
//        override fun doFlush() {
//            super.doFlush()
//            enumerator.force()
//        }
//
//        override fun doDispose() {
//            try {
//                super.doDispose()
//            } finally {
//                IOUtil.closeSafe(LOGGER, enumerator)
//            }
//        }
//    }
}
