#include "hungarian.h"
#include <algorithm>
#include <limits>
#include <cmath>

namespace safetycore_v2 {

std::vector<std::pair<int, int>> Hungarian::solve(
    const std::vector<std::vector<float>>& costs,
    float threshold) {

    if (costs.empty() || costs[0].empty()) {
        return {};
    }

    int rows = static_cast<int>(costs.size());
    int cols = static_cast<int>(costs[0].size());
    int n = std::max(rows, cols);

    // Pad cost matrix to square
    std::vector<std::vector<float>> matrix(n, std::vector<float>(n, threshold + 1.0f));
    for (int i = 0; i < rows; ++i) {
        for (int j = 0; j < cols; ++j) {
            matrix[i][j] = costs[i][j];
        }
    }

    // Step 1: Row reduction
    for (int i = 0; i < n; ++i) {
        float minVal = *std::min_element(matrix[i].begin(), matrix[i].end());
        for (int j = 0; j < n; ++j) {
            matrix[i][j] -= minVal;
        }
    }

    // Step 2: Column reduction
    for (int j = 0; j < n; ++j) {
        float minVal = std::numeric_limits<float>::max();
        for (int i = 0; i < n; ++i) {
            minVal = std::min(minVal, matrix[i][j]);
        }
        for (int i = 0; i < n; ++i) {
            matrix[i][j] -= minVal;
        }
    }

    // Initialize tracking arrays
    std::vector<int> rowAssign(n, -1);
    std::vector<int> colAssign(n, -1);
    std::vector<bool> rowCover(n, false);
    std::vector<bool> colCover(n, false);
    std::vector<int> pathRow(n * 2);
    std::vector<int> pathCol(n * 2);

    int step = 3;
    while (step != 7) {
        switch (step) {
            case 3: step3(matrix, colAssign, colCover, n, step); break;
            case 4: step4(matrix, rowCover, colCover, rowAssign, colAssign, pathRow, pathCol, n, step); break;
            case 5: step5(pathRow, pathCol, rowAssign, colAssign, rowCover, colCover, step); break;
            case 6: step6(matrix, rowCover, colCover, n, step); break;
        }
    }

    // Extract valid assignments
    std::vector<std::pair<int, int>> result;
    for (int j = 0; j < n; ++j) {
        int i = colAssign[j];
        if (i >= 0 && i < rows && j < cols && costs[i][j] <= threshold) {
            result.emplace_back(i, j);
        }
    }

    return result;
}

void Hungarian::step3(std::vector<std::vector<float>>& matrix,
                      std::vector<int>& colAssign,
                      std::vector<bool>& colCover,
                      int n, int& step) {
    for (int i = 0; i < n; ++i) {
        if (colAssign[i] >= 0) {
            colCover[i] = true;
        }
    }
    int coveredCols = 0;
    for (int i = 0; i < n; ++i) {
        if (colCover[i]) ++coveredCols;
    }
    step = (coveredCols >= n) ? 7 : 4;
}

void Hungarian::step4(std::vector<std::vector<float>>& matrix,
                      std::vector<bool>& rowCover,
                      std::vector<bool>& colCover,
                      std::vector<int>& rowAssign,
                      std::vector<int>& colAssign,
                      std::vector<int>& pathRow,
                      std::vector<int>& pathCol,
                      int n, int& step) {
    while (true) {
        // Find uncovered zero
        int foundRow = -1, foundCol = -1;
        for (int i = 0; i < n && foundRow < 0; ++i) {
            if (rowCover[i]) continue;
            for (int j = 0; j < n; ++j) {
                if (colCover[j]) continue;
                if (std::abs(matrix[i][j]) < 1e-6f) {
                    foundRow = i;
                    foundCol = j;
                    break;
                }
            }
        }

        if (foundRow < 0) {
            step = 6;
            return;
        }

        rowAssign[foundRow] = foundCol;
        int starCol = -1;
        for (int j = 0; j < n; ++j) {
            if (colAssign[j] == foundRow) {
                starCol = j;
                break;
            }
        }

        if (starCol >= 0) {
            rowCover[foundRow] = true;
            colCover[starCol] = false;
        } else {
            pathRow[0] = foundRow;
            pathCol[0] = foundCol;
            step = 5;
            return;
        }
    }
}

void Hungarian::step5(std::vector<int>& pathRow,
                      std::vector<int>& pathCol,
                      std::vector<int>& rowAssign,
                      std::vector<int>& colAssign,
                      std::vector<bool>& rowCover,
                      std::vector<bool>& colCover,
                      int& step) {
    int pathLen = 1;
    int n = static_cast<int>(colAssign.size());

    while (true) {
        int row = colAssign[pathCol[pathLen - 1]];
        if (row < 0) break;
        pathRow[pathLen] = row;
        pathCol[pathLen] = pathCol[pathLen - 1];
        ++pathLen;

        int col = rowAssign[row];
        pathRow[pathLen] = row;
        pathCol[pathLen] = col;
        ++pathLen;
    }

    // Augment path
    for (int i = 0; i < pathLen; i += 2) {
        colAssign[pathCol[i]] = pathRow[i];
    }

    // Clear covers and primes
    std::fill(rowCover.begin(), rowCover.end(), false);
    std::fill(colCover.begin(), colCover.end(), false);
    std::fill(rowAssign.begin(), rowAssign.end(), -1);

    step = 3;
}

void Hungarian::step6(std::vector<std::vector<float>>& matrix,
                      std::vector<bool>& rowCover,
                      std::vector<bool>& colCover,
                      int n, int& step) {
    // Find minimum uncovered value
    float minVal = std::numeric_limits<float>::max();
    for (int i = 0; i < n; ++i) {
        if (rowCover[i]) continue;
        for (int j = 0; j < n; ++j) {
            if (colCover[j]) continue;
            minVal = std::min(minVal, matrix[i][j]);
        }
    }

    // Add to covered rows, subtract from uncovered columns
    for (int i = 0; i < n; ++i) {
        for (int j = 0; j < n; ++j) {
            if (rowCover[i]) matrix[i][j] += minVal;
            if (!colCover[j]) matrix[i][j] -= minVal;
        }
    }

    step = 4;
}

} // namespace safetycore_v2
