package edu.purdue.dblab

import org.apache.spark.{SparkContext, SparkConf, SparkException, Logging}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.mllib.linalg.{Matrix => MLMatrix, SparseMatrix, DenseMatrix}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.math.Ordering.Implicits._
import scala.collection.mutable.PriorityQueue

/**
 * Created by yongyangyu on 7/15/15.
 */
class BlockPartitionMatrix (
    val blocks: RDD[((Int, Int), MLMatrix)],
    val ROWS_PER_BLK: Int,
    val COLS_PER_BLK: Int,
    private var nrows: Long,
    private var ncols: Long) extends Matrix with Logging {

    val ROW_BLK_NUM = math.ceil(nRows() * 1.0 / ROWS_PER_BLK).toInt
    val COL_BLK_NUM = math.ceil(nCols() * 1.0 / COLS_PER_BLK).toInt

    override def nRows(): Long = {
        if (nrows <= 0L) {
            getDimension()
        }
        nrows
    }

    override def nCols(): Long = {
        if (ncols <= 0L) {
            getDimension()
        }
        ncols
    }

    override def nnz(): Long = {
        blocks.map{ mat =>
            mat._2 match {
              case mdense: DenseMatrix => mdense.values.count( _ != 0).toLong
              case msparse: SparseMatrix => msparse.values.count( _ != 0).toLong
              case _ => 0L
            }
        }.sum().toLong
    }

    private type MatrixBlk = ((Int, Int), MLMatrix)
    private type PartitionScheme = (Int, Int)

    private def genBlockPartitioner(): BlockCyclicPartitioner = {
        BlockCyclicPartitioner(ROW_BLK_NUM, COL_BLK_NUM, suggestedNumPartitions = blocks.partitions.size)
    }

    private lazy val blkInfo = blocks.mapValues(block => (block.numRows, block.numCols)).cache()

    private def getDimension(): Unit = {
        val (rows, cols) = blkInfo.map { x =>
            val blkRowIdx = x._1._1
            val blkColIdx = x._1._1
            val rowSize = x._2._1
            val colSize = x._2._2
            (blkRowIdx.toLong * ROWS_PER_BLK + rowSize, blkRowIdx.toLong * COLS_PER_BLK + colSize)
        }.reduce { (x, y) =>
          (math.max(x._1, y._1), math.max(x._2, y._2))
        }
        if (nrows <= 0) nrows = rows
        assert(rows <= nrows, s"Number of rows $rows is more than claimed $nrows")
        if (ncols <= 0) ncols = cols
        assert(cols <= ncols, s"Number of cols $cols is more than claimed $ncols")
    }

    /*
     * Caches the underlying RDD
     */
    def cache(): this.type = {
        blocks.cache()
        this
    }

    /*
     * Persists the underlying RDD with specified storage level.
     */
    def persist(storageLevel: StorageLevel): this.type = {
        blocks.persist(storageLevel)
        this
    }

    /*
     * Validates the block matrix to find out any errors or exceptions.
     */
    def validate(): Unit = {
        logDebug("Validating block partition matrices ...")
        getDimension()
        logDebug("Block partition matrices dimensions are OK ...")
        // check if there exists duplicates for the keys, i.e., duplicate indices
        blkInfo.countByKey().foreach{ case (key, count) =>
            if (count > 1) {
                throw new SparkException(s"Found duplicate indices for the same block, key is $key.")
            }
        }
        logDebug("Block partition matrices indices are OK ...")
        val dimMsg = s"Dimensions different than ROWS_PER_BLK: $ROWS_PER_BLK, and " +
                    s"COLS_PER_BLK: $COLS_PER_BLK. Blocks on the right and bottom edges may have " +
                    s"smaller dimensions. The problem may be fixed by repartitioning the matrix."
        // check for size of each individual block
        blkInfo.foreach{ case ((blkRowIdx, blkColInx), (m, n)) =>
            if ((blkRowIdx < ROW_BLK_NUM - 1 && m != ROWS_PER_BLK) ||
                (blkRowIdx == ROW_BLK_NUM - 1 && (m <= 0 || m > ROWS_PER_BLK))) {
                throw new SparkException(s"Matrix block at ($blkRowIdx, $blkColInx) has " + dimMsg)
            }
            if ((blkColInx < COL_BLK_NUM - 1 && n != COLS_PER_BLK) ||
                (blkColInx == COL_BLK_NUM - 1 && (n <= 0 || n > COLS_PER_BLK))) {
                throw new SparkException(s"Matrix block at ($blkRowIdx, $blkColInx) has " + dimMsg)
            }
        }
        logDebug("Block partition matrix dimensions are OK ...")
        logDebug("Block partition matrix is valid.")
    }

    def transpose(): BlockPartitionMatrix = {
        val transposeBlks = blocks.map {
            case ((blkRowIdx, blkColIdx), mat) => ((blkColIdx, blkRowIdx), mat.transpose)
        }
        new BlockPartitionMatrix(transposeBlks, COLS_PER_BLK, ROWS_PER_BLK, nCols(), nRows())
    }

    def t: BlockPartitionMatrix = transpose()

    /*
     * Collect the block partitioned matrix on the driver side for debugging purpose only.
     */
    def toLocalMatrix(): MLMatrix = {
        require(nRows() < Int.MaxValue, s"Number of rows should be smaller than Int.MaxValue, but " +
        s"found ${nRows()}")
        require(nCols() < Int.MaxValue, s"Number of cols should be smaller than Int.MaxValue, but " +
        s"found ${nCols()}")
        require(nnz() < Int.MaxValue, s"Total number of the entries should be smaller than Int.MaxValue, but " +
        s"found ${nnz()}")
        val m = nRows().toInt
        val n = nCols().toInt
        val memSize = m * n / 131072  // m-by-n * 8 byte / (1024 * 1024) MB
        if (memSize > 500) logWarning(s"Storing local matrix requires $memSize MB")
        val localBlks = blocks.collect()
        val values = Array.fill(m * n)(0.0)
        localBlks.foreach{
            case ((blkRowIdx, blkColIdx), mat) =>
                val rowOffset = blkRowIdx * ROWS_PER_BLK
                val colOffset = blkColIdx * COLS_PER_BLK
                // (i, j) --> (i + rowOffset, j + colOffset)
                for (i <- 0 until mat.numRows; j <- 0 until mat.numCols) {
                    val indexOffset = (j + colOffset) * m + (rowOffset + i)
                    values(indexOffset) = mat(i, j)
                }
        }
        new DenseMatrix(m, n, values)
    }

    def *(alpha: Double): BlockPartitionMatrix = {
        multiplyScalar(alpha)
    }

    def *:(alpha: Double): BlockPartitionMatrix = {
        multiplyScalar(alpha)
    }

    def multiplyScalar(alpha: Double): BlockPartitionMatrix = {
        val rdd = blocks.map {
            case ((rowIdx, colIdx), mat) => ((rowIdx, colIdx), LocalMatrix.multiplyScalar(alpha, mat))
        }
        new BlockPartitionMatrix(rdd, ROWS_PER_BLK, COLS_PER_BLK, nRows(), nCols())
    }

    def +(other: BlockPartitionMatrix, dimension: PartitionScheme = (ROWS_PER_BLK, COLS_PER_BLK)): BlockPartitionMatrix = {
        add(other, dimension)
    }

    /*
     * Adds two block partitioned matrices together. The matrices must have the same size but may have
     * different partitioning schemes, i.e., different `ROWS_PER_BLK` and `COLS_PER_BLK` values.
     * @param dimension, specifies the (ROWS_PER_PARTITION, COLS_PER_PARTITION) of the result
     */
    def add(other: BlockPartitionMatrix, dimension: PartitionScheme = (ROWS_PER_BLK, COLS_PER_BLK)): BlockPartitionMatrix = {
        require(nRows() == other.nRows(), s"Two matrices must have the same number of rows. " +
        s"A.rows: ${nRows()}, B.rows: ${other.nRows()}")
        require(nCols() == other.nCols(), s"Two matrices must have the same number of cols. " +
        s"A.cols: ${nCols()}, B.cols: ${other.nCols()}")
        // simply case when both matrices are partitioned in the same fashion
        if (ROWS_PER_BLK == other.ROWS_PER_BLK && COLS_PER_BLK == other.COLS_PER_BLK &&
            ROWS_PER_BLK == dimension._1 && COLS_PER_BLK == dimension._2) {
            addSameDim(blocks, other.blocks, ROWS_PER_BLK, COLS_PER_BLK)
        }
        // need to repartition the matrices according to the specification
        else {
            var (repartA, repartB) = (true, true)
            if (ROWS_PER_BLK == dimension._1 && COLS_PER_BLK == dimension._2) {
                repartA = false
            }
            if (other.ROWS_PER_BLK == dimension._1 && other.COLS_PER_BLK == dimension._2) {
                repartB = false
            }
            var (rddA, rddB) = (blocks, other.blocks)
            if (repartA) {
                rddA = getNewBlocks(blocks, ROWS_PER_BLK, COLS_PER_BLK, dimension._1, dimension._2)
                /*rddA.foreach{
                    x =>
                        val (row, col) = x._1
                        val mat = x._2
                        println(s"row: $row, col: $col, Matrix A")
                        println(mat)
                }*/
            }
            if (repartB) {
                rddB = getNewBlocks(other.blocks, other.ROWS_PER_BLK, other.COLS_PER_BLK, dimension._1, dimension._2)
                /*rddB.foreach{
                    x =>
                        val (row, col) = x._1
                        val mat = x._2
                        println(s"row: $row, col: $col, Matrix B")
                        println(mat)
                }*/
            }
            // place holder
            addSameDim(rddA, rddB, dimension._1, dimension._2)
        }
    }

    private def addSameDim(rddA: RDD[((Int, Int), MLMatrix)], rddB: RDD[((Int, Int), MLMatrix)],
                            RPB: Int, CPB: Int): BlockPartitionMatrix = {
        val addBlks = rddA.cogroup(rddB, genBlockPartitioner())
          .map {
            case ((rowIdx, colIdx), (a, b)) =>
                if (a.size > 1 || b.size > 1) {
                    throw new SparkException("There are multiple MatrixBlocks with indices: " +
                      s"($rowIdx, $colIdx). Please remove the duplicate and try again.")
                }
                if (a.isEmpty) {
                    new MatrixBlk((rowIdx, colIdx), b.head)
                }
                else if (b.isEmpty) {
                    new MatrixBlk((rowIdx, colIdx), a.head)
                }
                else {
                    new MatrixBlk((rowIdx, colIdx), LocalMatrix.add(a.head, b.head))
                }
        }
        new BlockPartitionMatrix(addBlks, RPB, CPB, nRows(), nCols())
    }

    private def getNewBlocks(rdd: RDD[((Int, Int), MLMatrix)],
                             curRPB: Int, curCPB: Int, targetRPB: Int, targetCPB: Int): RDD[((Int, Int), MLMatrix)] = {
        val rddNew = rePartition(rdd, curRPB, curCPB, targetRPB, targetCPB)
        rddNew.groupByKey(genBlockPartitioner()).map {
            case ((rowIdx, colIdx), iter) =>
                val rowStart = rowIdx * targetRPB
                val rowEnd = math.min((rowIdx + 1) * targetRPB - 1, nRows() - 1)
                val colStart = colIdx * targetCPB
                val colEnd = math.min((colIdx + 1) * targetCPB - 1, nCols() - 1)
                val (m, n) = (rowEnd.toInt - rowStart + 1, colEnd.toInt - colStart + 1)  // current blk size
                val values = Array.fill(m * n)(0.0)
                val (rowOffset, colOffset) = (rowIdx * targetRPB, colIdx * targetCPB)
                for (elem <- iter) {
                    var arr = elem._5
                    var rowSize = elem._2 - elem._1 + 1
                    for (j <- elem._3 to elem._4; i <- elem._1 to elem._2) {
                        var idx = (j - elem._3) * rowSize + (i - elem._1)
                        // assign arr(idx) to a proper position
                        var (ridx, cidx) = (i - rowOffset, j - colOffset)
                        values(cidx.toInt * m + ridx.toInt) = arr(idx.toInt)
                    }
                }
                // 50% or more 0 elements, use sparse matrix format
                if (values.count(entry => entry > 0.0) > 0.5 * values.length ) {
                    ((rowIdx, colIdx), new DenseMatrix(m, n, values))
                }
                else {
                    ((rowIdx, colIdx), new DenseMatrix(m, n, values).toSparse)
                }

        }

    }
    // RPB -- #rows_per_blk, CPB -- #cols_per_blk
    private def rePartition(rdd: RDD[((Int, Int), MLMatrix)],
                            curRPB: Int, curCPB: Int, targetRPB: Int,
                            targetCPB: Int): RDD[((Int, Int), (Long, Long, Long, Long, Array[Double]))] = {
        rdd.map { case ((rowIdx, colIdx), mat) =>
            val rowStart: Long = rowIdx * curRPB
            val rowEnd: Long = math.min((rowIdx + 1) * curRPB - 1, nRows() - 1)
            val colStart: Long = colIdx * curCPB
            val colEnd: Long = math.min((colIdx + 1) * curCPB - 1, nCols() - 1)
            val (x1, x2) = ((rowStart / targetRPB).toInt, (rowEnd / targetRPB).toInt)
            val (y1, y2) = ((colStart / targetCPB).toInt, (colEnd / targetCPB).toInt)
            val res = ArrayBuffer[((Int, Int), (Long, Long, Long, Long, Array[Double]))]()
            for (r <- x1 to x2; c <- y1 to y2) {
                // (r, c) entry for the target partition scheme
                val rowStartNew: Long = r * targetRPB
                val rowEndNew: Long = math.min((r + 1) * targetRPB - 1, nRows() - 1)
                val colStartNew: Long = c * targetCPB
                val colEndNew: Long = math.min((c + 1) * targetCPB - 1, nCols() - 1)
                val rowRange = findIntersect(rowStart, rowEnd, rowStartNew, rowEndNew)
                val colRange = findIntersect(colStart, colEnd, colStartNew, colEndNew)
                val (rowOffset, colOffset) = (rowIdx * curRPB, colIdx * curCPB)
                val values = ArrayBuffer[Double]()
                for (j <- colRange; i <- rowRange) {
                    values += mat((i - rowOffset).toInt, (j - colOffset).toInt)
                }
                val elem = (rowRange(0), rowRange(rowRange.length - 1), colRange(0), colRange(colRange.length - 1), values.toArray)
                val entry = ((r, c), elem)
                res += entry
            }
            res.toArray
        }.flatMap(x => x)
    }

    private def findIntersect(s1: Long, e1: Long, s2: Long, e2: Long): Array[Long] = {
        val tmp = ArrayBuffer[Long]()
        var (x, y) = (s1, s2)
        while (x <= e1 && y <= e2) {
            if (x == y) {
                tmp += x
                x += 1
                y += 1
            }
            else if (x < y) {
                x += 1
            }
            else {
                y += 1
            }
        }
        tmp.toArray
    }

    def %*%(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        multiply(other)
    }

    // TODO: currently the repartitioning of A * B will perform on matrix B
    // TODO: if blocks of B do not conform with blocks of A, need to find an optimal strategy
    def multiply(other: BlockPartitionMatrix): BlockPartitionMatrix = {
        require(nCols() == other.nRows(), s"#cols of A should be equal to #rows of B, but found " +
        s"A.numCols = ${nCols()}, B.numRows = ${other.nRows()}")
        var rddB = other.blocks
        if (COLS_PER_BLK != other.ROWS_PER_BLK) {
            logWarning(s"Repartition Matrix B since A.col_per_blk = $COLS_PER_BLK and B.row_per_blk = ${other.ROWS_PER_BLK}")
            rddB = getNewBlocks(other.blocks, other.ROWS_PER_BLK, other.COLS_PER_BLK, COLS_PER_BLK, COLS_PER_BLK)
        }
        // other.ROWS_PER_BLK = COLS_PER_BLK and square blk for other
        val OTHER_COL_BLK_NUM = math.ceil(other.nCols() * 1.0 / COLS_PER_BLK).toInt
        val resPartitioner = BlockCyclicPartitioner(ROW_BLK_NUM, OTHER_COL_BLK_NUM, math.max(blocks.partitions.length, rddB.partitions.length))
        val flatA = blocks.flatMap{ case ((rowIdx, colIdx), blk) =>
            Iterator.tabulate(OTHER_COL_BLK_NUM)(j => ((rowIdx, j, colIdx), blk))
        }
        val flatB = rddB.flatMap{ case ((rowIdx, colIdx), blk) =>
            Iterator.tabulate(ROW_BLK_NUM)(i => ((i, colIdx, rowIdx), blk))
        }
        val newBlks: RDD[MatrixBlk] = flatA.cogroup(flatB, resPartitioner)
            .flatMap{ case ((rowIdx, colIdx, _), (a, b)) =>
                    if (a.size > 1 || b.size > 1) {
                        throw new SparkException("There are multiple blocks with indices: " +
                        s"($rowIdx, $colIdx).")
                    }
                    if (a.nonEmpty && b.nonEmpty) {
                        val c = (a.head, b.head) match {
                            case (dm1: DenseMatrix, dm2: DenseMatrix) => dm1.multiply(dm2)
                            case (dm: DenseMatrix, sp: SparseMatrix) => dm.multiply(sp.toDense)
                            case (sp: SparseMatrix, dm: DenseMatrix) => sp.multiply(dm)
                            case (sp1: SparseMatrix, sp2: SparseMatrix) => LocalMatrix.multiplySparseSparse(sp1, sp2)
                            case _ => throw new SparkException(s"Unsupported matrix type ${b.head.getClass.getName}")
                        }
                        Iterator(((rowIdx, colIdx), LocalMatrix.toBreeze(c)))
                    }
                    else {
                        Iterator()
                    }
                }
            .reduceByKey(resPartitioner, _ + _)
            .mapValues(LocalMatrix.fromBreeze(_))
        new BlockPartitionMatrix(newBlks, ROWS_PER_BLK, COLS_PER_BLK, nRows(), other.nCols())
    }

    /*
     * Compute top-k largest elements from the block partitioned matrix.
     */
    def topK(k: Long): Array[((Long, Long), Double)] = {
        val res = blocks.map { blk =>
            val rowIdx = blk._1._1
            val colIdx = blk._1._2
            val matrix = blk._2
            val pq = new mutable.PriorityQueue[((Int, Int), Double)]()(Ordering.by(orderByValueInt))
            for (i <- 0 until matrix.numRows; j <- 0 until matrix.numCols) {
                if (pq.size < k) {
                    pq.enqueue(((i, j), matrix(i, j)))
                }
                else {
                    pq.enqueue(((i, j), matrix(i, j)))
                    pq.dequeue()
                }
            }
            ((rowIdx, colIdx), pq.toArray)
        }.collect()
        val q = new mutable.PriorityQueue[((Long, Long), Double)]()(Ordering.by(orderByValueDouble))
        for (elem <- res) {
            val offset = (elem._1._1 * ROWS_PER_BLK.toLong, elem._1._2 * COLS_PER_BLK.toLong)
            for (entry <- elem._2) {
                if (q.size < k) {
                    q.enqueue(((offset._1 + entry._1._1, offset._2 + entry._1._2), entry._2))
                }
                else {
                    q.enqueue(((offset._1 + entry._1._1, offset._2 + entry._1._2), entry._2))
                    q.dequeue()
                }
            }
        }
        q.toArray
    }

    private def orderByValueInt(t: ((Int, Int), Double)) = {
        - t._2
    }

    private def orderByValueDouble(t: ((Long, Long), Double)) = {
        - t._2
    }
}

object BlockPartitionMatrix {
    // TODO: finish some helper factory methods
    def createFromCoordinateEntries(entries: RDD[Entry],
                                    ROWS_PER_BLK: Int,
                                    COLS_PER_BLK: Int,
                                    ROW_NUM: Long = 0,
                                    COL_NUM: Long = 0): BlockPartitionMatrix = {
        require(ROWS_PER_BLK > 0, s"ROWS_PER_BLK needs to be greater than 0. " +
        s"But found ROWS_PER_BLK = $ROWS_PER_BLK")
        require(COLS_PER_BLK > 0, s"COLS_PER_BLK needs to be greater than 0. " +
        s"But found COLS_PER_BLK = $COLS_PER_BLK")
        var colSize = entries.map(x => x.col).max() + 1
        if (COL_NUM > 0 && colSize > COL_NUM) {
            println(s"Computing colSize is greater than COL_NUM, colSize = $colSize, COL_NUM = $COL_NUM")
        }
        if (COL_NUM > colSize) colSize = COL_NUM
        var rowSize = entries.map(x => x.row).max() + 1
        if (ROW_NUM > 0 && rowSize > ROW_NUM) {
            println(s"Computing rowSize is greater than ROW_NUM, rowSize = $rowSize, ROW_NUM = $ROW_NUM")
        }
        if (ROW_NUM > rowSize) rowSize = ROW_NUM
        val ROW_BLK_NUM = math.ceil(rowSize * 1.0 / ROWS_PER_BLK).toInt
        val COL_BLK_NUM = math.ceil(colSize * 1.0 / COLS_PER_BLK).toInt
        val partitioner = BlockCyclicPartitioner(ROW_BLK_NUM, COL_BLK_NUM, entries.partitions.length)
        val blocks: RDD[((Int, Int), MLMatrix)] = entries.map { entry =>
            val blkRowIdx = (entry.row / ROWS_PER_BLK).toInt
            val blkColIdx = (entry.col / COLS_PER_BLK).toInt
            val rowId = entry.row % ROWS_PER_BLK
            val colId = entry.col % COLS_PER_BLK
            ((blkRowIdx, blkColIdx), (rowId.toInt, colId.toInt, entry.value))
        }.groupByKey(partitioner).map { case ((blkRowIdx, blkColIdx), entry) =>
            val effRows = math.min(rowSize - blkRowIdx.toLong * ROWS_PER_BLK, ROWS_PER_BLK).toInt
            val effCols = math.min(colSize - blkColIdx.toLong * COLS_PER_BLK, COLS_PER_BLK).toInt
            ((blkRowIdx, blkColIdx), SparseMatrix.fromCOO(effRows, effCols, entry))
        }
        new BlockPartitionMatrix(blocks, ROWS_PER_BLK, COLS_PER_BLK, rowSize, colSize)
    }

    def PageRankMatrixFromCoordinateEntries(entries: RDD[Entry],
                                            ROWS_PER_BLK: Int,
                                            COLS_PER_BLK: Int): BlockPartitionMatrix = {
        require(ROWS_PER_BLK > 0, s"ROWS_PER_BLK needs to be greater than 0. " +
        s"But found ROWS_PER_BLK = $ROWS_PER_BLK")
        require(COLS_PER_BLK > 0, s"COLS_PER_BLK needs to be greater than 0. " +
        s"But found COLS_PER_BLK = $COLS_PER_BLK")
        val rowSize = entries.map(x => x.row).max() + 1
        val colSize = entries.map(x => x.col).max() + 1
        val size = math.max(rowSize, colSize)   // make sure the generating matrix is a square matrix
        val wRdd = entries.map(entry => (entry.row, entry))
        .groupByKey().map { x =>
            (x._1, 1.0 / x._2.size)
        }
        val prEntries = entries.map { entry =>
            (entry.row, entry)
        }.join(wRdd)
        .map { record =>
            val rid = record._2._1.col
            val cid = record._2._1.row
            val v = record._2._1.value * record._2._2
            Entry(rid, cid, v)
        }
        createFromCoordinateEntries(prEntries, ROWS_PER_BLK, COLS_PER_BLK, size, size)
    }

    def onesMatrixList(nrows: Long, ncols: Long, ROWS_PER_BLK: Int, COLS_PER_BLK: Int): List[((Int, Int), MLMatrix)] = {
        val ROW_BLK_NUM = math.ceil(nrows * 1.0 / ROWS_PER_BLK).toInt
        val COL_BLK_NUM = math.ceil(ncols * 1.0 / COLS_PER_BLK).toInt
        var res = scala.collection.mutable.LinkedList[((Int, Int), MLMatrix)]()
        for (i <- 0 until ROW_BLK_NUM; j <- 0 until COL_BLK_NUM) {
            val rowSize = math.min(ROWS_PER_BLK, nrows - i * ROWS_PER_BLK).toInt
            val colSize = math.min(COLS_PER_BLK, ncols - j * COLS_PER_BLK).toInt
            res = res :+ ((i, j), DenseMatrix.ones(rowSize, colSize))
        }
        res.toList
    }
}


object TestBlockPartition {
    def main (args: Array[String]) {
        val conf = new SparkConf()
          .setMaster("local[4]")
          .setAppName("Test for block partition matrices")
          .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
          .set("spark.shuffle.consolidateFiles", "true")
          .set("spark.shuffle.compress", "false")
        val sc = new SparkContext(conf)
        // the following test the block matrix addition and multiplication
        /*val m1 = new DenseMatrix(3,3,Array[Double](1,7,13,2,8,14,3,9,15))
        val m2 = new DenseMatrix(3,3,Array[Double](4,10,16,5,11,17,6,12,18))
        val m3 = new DenseMatrix(3,3,Array[Double](19,25,31,20,26,32,21,27,33))
        val m4 = new DenseMatrix(3,3,Array[Double](22,28,34,23,29,35,24,30,36))
        val n1 = new DenseMatrix(4,4,Array[Double](1,1,1,3,1,1,1,3,1,1,1,3,2,2,2,4))
        val n2 = new DenseMatrix(4,2,Array[Double](2,2,2,4,2,2,2,4))
        val n3 = new DenseMatrix(2,4,Array[Double](3,3,3,3,3,3,4,4))
        val n4 = new DenseMatrix(2,2,Array[Double](4,4,4,4))
        val arr1 = Array[((Int, Int), MLMatrix)](((0,0),m1), ((0,1), m2), ((1,0), m3), ((1,1), m4))
        val arr2 = Array[((Int, Int), MLMatrix)](((0,0),n1), ((0,1), n2), ((1,0), n3), ((1,1), n4))
        val rdd1 = sc.parallelize(arr1, 2)
        val rdd2 = sc.parallelize(arr2, 2)
        val mat1 = new BlockPartitionMatrix(rdd1, 3, 3, 6, 6)
        val mat2 = new BlockPartitionMatrix(rdd2, 4, 4, 6, 6)
        //println(mat1.add(mat2).toLocalMatrix())
        /*  addition
         *  2.0   3.0   4.0   6.0   7.0   8.0
            8.0   9.0   10.0  12.0  13.0  14.0
            14.0  15.0  16.0  18.0  19.0  20.0
            22.0  23.0  24.0  26.0  27.0  28.0
            28.0  29.0  30.0  32.0  33.0  34.0
            34.0  35.0  36.0  38.0  39.0  40.0
         */
        println(mat1.multiply(mat2).toLocalMatrix())
        /*   multiplication
             51    51    51    72    72    72
             123   123   123   180   180   180
             195   195   195   288   288   288
             267   267   267   396   396   396
             339   339   339   504   504   504
             411   411   411   612   612   612
         */
         */

        val mat = List[(Long, Long)]((0, 0), (0,1), (0,2), (0,3), (0, 4), (0, 5), (1, 0), (1, 2),
            (2, 3), (2, 4), (3,1), (3,2), (3, 4), (4, 5), (5, 4))
        val CooRdd = sc.parallelize(mat, 2).map(x => Entry(x._1, x._2, 1.0))
        val matrix = BlockPartitionMatrix.PageRankMatrixFromCoordinateEntries(CooRdd, 3, 3).cache()
        val vec = BlockPartitionMatrix.onesMatrixList(6, 1, 3, 3)//List[((Int, Int), MLMatrix)](((0, 0), DenseMatrix.ones(3, 1)), ((1, 0), DenseMatrix.ones(3, 1)))
        val vecRdd = sc.parallelize(vec, 2)
        var x = new BlockPartitionMatrix(vecRdd, 3, 3, 6, 1).multiplyScalar(1.0 / 6)
        val v = new BlockPartitionMatrix(vecRdd, 3, 3, 6, 1).multiplyScalar(1.0 / 6)
        val alpha = 0.85
        for (i <- 0 until 10) {
            x = alpha *: (matrix %*% x) + ((1.0-alpha) *: v, (3,3))
            //x = matrix.multiply(x).multiplyScalar(alpha).add(v.multiplyScalar(1-alpha), (3,3))
        }
        println(x.toLocalMatrix())
        sc.stop()
    }
}