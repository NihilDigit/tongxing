#ifndef SAFETYCORE_V2_HUNGARIAN_H
#define SAFETYCORE_V2_HUNGARIAN_H

#include <vector>

namespace safetycore_v2 {

/**
 * Hungarian algorithm (Kuhn-Munkres) for optimal bipartite matching.
 * Solves minimum-cost assignment problem in O(n³).
 */
class Hungarian {
public:
    /**
     * Solve assignment problem.
     * @param costs Cost matrix (rows × cols)
     * @param threshold Maximum cost allowed for assignment
     * @return Vector of (row, col) pairs representing optimal assignment
     */
    static std::vector<std::pair<int, int>> solve(
        const std::vector<std::vector<float>>& costs,
        float threshold = 1e9f);

private:
    static void step3(std::vector<std::vector<float>>& matrix,
                      std::vector<int>& colAssign,
                      std::vector<bool>& colCover,
                      int n, int& step);

    static void step4(std::vector<std::vector<float>>& matrix,
                      std::vector<bool>& rowCover,
                      std::vector<bool>& colCover,
                      std::vector<int>& rowAssign,
                      std::vector<int>& colAssign,
                      std::vector<int>& pathRow,
                      std::vector<int>& pathCol,
                      int n, int& step);

    static void step5(std::vector<int>& pathRow,
                      std::vector<int>& pathCol,
                      std::vector<int>& rowAssign,
                      std::vector<int>& colAssign,
                      std::vector<bool>& rowCover,
                      std::vector<bool>& colCover,
                      int& step);

    static void step6(std::vector<std::vector<float>>& matrix,
                      std::vector<bool>& rowCover,
                      std::vector<bool>& colCover,
                      int n, int& step);
};

} // namespace safetycore_v2

#endif // SAFETYCORE_V2_HUNGARIAN_H
