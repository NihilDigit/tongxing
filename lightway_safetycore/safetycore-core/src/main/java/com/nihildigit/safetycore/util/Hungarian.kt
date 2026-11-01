package com.nihildigit.safetycore.util

import kotlin.math.min

/**
 * Hungarian algorithm (Kuhn-Munkres) for optimal bipartite matching.
 * Solves minimum-cost assignment problem in O(n³).
 *
 * Based on: https://en.wikipedia.org/wiki/Hungarian_algorithm
 */
object Hungarian {

    /**
     * Assigns rows to columns in a cost matrix to minimize total cost.
     * Returns an IntArray where result[row] = column (or -1 if unassigned).
     *
     * Standard Hungarian algorithm with augmenting paths.
     * Guarantees optimal matching (not greedy).
     *
     * @param costs Cost matrix (rows × cols). Will be modified during computation.
     * @param threshold Maximum cost allowed for an assignment. Pairs exceeding this are not matched.
     * @return IntArray of size rows where result[i] = j means row i is assigned to col j (-1 if unassigned).
     */
    fun assign(costs: Array<FloatArray>, threshold: Float = Float.MAX_VALUE): IntArray {
        if (costs.isEmpty()) return IntArray(0)
        return assign(costs, costs.size, costs[0].size, threshold)
    }

    /**
     * Assigns rows to columns with explicit row/col counts to avoid copying submatrices.
     */
    fun assign(costs: Array<FloatArray>, rowCount: Int, colCount: Int, threshold: Float = Float.MAX_VALUE): IntArray {
        if (costs.isEmpty() || rowCount == 0 || colCount == 0) return IntArray(0)

        val rows = rowCount.coerceAtMost(costs.size)
        val cols = colCount.coerceAtMost(costs[0].size)
        val n = maxOf(rows, cols)

        // Pad cost matrix to square (n×n) with high costs
        val padded = Array(n) { FloatArray(n) { threshold + 1f } }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                padded[i][j] = costs[i][j]
            }
        }

        // Step 1: Row reduction
        for (i in 0 until n) {
            val minVal = padded[i].minOrNull() ?: 0f
            for (j in 0 until n) {
                padded[i][j] -= minVal
            }
        }

        // Step 2: Column reduction
        for (j in 0 until n) {
            var minVal = Float.MAX_VALUE
            for (i in 0 until n) {
                if (padded[i][j] < minVal) minVal = padded[i][j]
            }
            for (i in 0 until n) {
                padded[i][j] -= minVal
            }
        }

        // Step 3: Augmenting path algorithm (not greedy)
        val rowAssign = IntArray(n) { -1 }
        val colAssign = IntArray(n) { -1 }
        val rowCover = BooleanArray(n)
        val colCover = BooleanArray(n)
        val pathRow = IntArray(n * 2)
        val pathCol = IntArray(n * 2)

        var step = 3
        while (step != 7) {
            when (step) {
                3 -> step = step3(padded, colAssign, colCover, n)
                4 -> step = step4(padded, rowCover, colCover, rowAssign, colAssign, pathRow, pathCol, n)
                5 -> step = step5(pathRow, pathCol, rowAssign, colAssign, rowCover, colCover)
                6 -> step = step6(padded, rowCover, colCover, n)
            }
        }

        // Extract valid assignments (within threshold and bounds)
        val result = IntArray(rows) { -1 }
        for (i in 0 until rows) {
            val j = colAssign.indexOfFirst { it == i }
            if (j in 0 until cols && costs[i][j] <= threshold) {
                result[i] = j
            }
        }

        return result
    }

    // Step 3: Cover each column with an assigned zero
    private fun step3(matrix: Array<FloatArray>, colAssign: IntArray, colCover: BooleanArray, n: Int): Int {
        for (i in 0 until n) {
            if (colAssign[i] >= 0) {
                colCover[i] = true
            }
        }
        val coveredCols = colCover.count { it }
        return if (coveredCols >= n) 7 else 4
    }

    // Step 4: Find uncovered zeros and create augmenting paths
    private fun step4(matrix: Array<FloatArray>, rowCover: BooleanArray, colCover: BooleanArray,
                      rowAssign: IntArray, colAssign: IntArray,
                      pathRow: IntArray, pathCol: IntArray, n: Int): Int {
        while (true) {
            val (row, col) = findUncoveredZero(matrix, rowCover, colCover, n) ?: return 6

            rowAssign[row] = col
            val starCol = colAssign.indexOfFirst { it == row }
            if (starCol >= 0) {
                // Star in this row: cover row, uncover column
                rowCover[row] = true
                colCover[starCol] = false
            } else {
                // No star in this row: found augmenting path
                pathRow[0] = row
                pathCol[0] = col
                return 5
            }
        }
    }

    // Step 5: Augment path (swap stars and primes)
    private fun step5(pathRow: IntArray, pathCol: IntArray,
                      rowAssign: IntArray, colAssign: IntArray,
                      rowCover: BooleanArray, colCover: BooleanArray): Int {
        var pathLen = 1
        while (true) {
            val row = colAssign[pathCol[pathLen - 1]]
            if (row < 0) break
            pathRow[pathLen] = row
            pathCol[pathLen] = pathCol[pathLen - 1]
            pathLen++

            val col = rowAssign[row]
            pathRow[pathLen] = row
            pathCol[pathLen] = col
            pathLen++
        }

        // Augment: swap stars and primes along path
        for (i in 0 until pathLen step 2) {
            colAssign[pathCol[i]] = pathRow[i]
        }

        // Clear covers and primes
        rowCover.fill(false)
        colCover.fill(false)
        rowAssign.fill(-1)

        return 3
    }

    // Step 6: Add/subtract minimum uncovered value
    private fun step6(matrix: Array<FloatArray>, rowCover: BooleanArray, colCover: BooleanArray, n: Int): Int {
        val minVal = findUncoveredMin2(matrix, rowCover, colCover, n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (rowCover[i]) matrix[i][j] += minVal
                if (!colCover[j]) matrix[i][j] -= minVal
            }
        }
        return 4
    }

    private fun findUncoveredZero(matrix: Array<FloatArray>, rowCover: BooleanArray, colCover: BooleanArray, n: Int): Pair<Int, Int>? {
        for (i in 0 until n) {
            if (rowCover[i]) continue
            for (j in 0 until n) {
                if (colCover[j]) continue
                if (matrix[i][j] == 0f) return Pair(i, j)
            }
        }
        return null
    }

    private fun findUncoveredMin2(matrix: Array<FloatArray>, rowCover: BooleanArray, colCover: BooleanArray, n: Int): Float {
        var minVal = Float.MAX_VALUE
        for (i in 0 until n) {
            if (rowCover[i]) continue
            for (j in 0 until n) {
                if (colCover[j]) continue
                if (matrix[i][j] < minVal) minVal = matrix[i][j]
            }
        }
        return minVal
    }

}
