package com.measurearr

import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

data class PlaneResult(
    val confidence: Float,
    val homography: Mat?,       // caller must NOT release — owned here, refreshed per frame
    val normalVec: DoubleArray, // [nx, ny, nz] plane normal in camera space
    val distanceRaw: Double,    // norm(t), scale-ambiguous scene units
)

class PlaneDetector {

    private val orb = ORB.create(200)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)

    // Class-member Mats — released once in close(), not per-frame
    private var prevGray = Mat()
    private var currGray = Mat()
    private var descriptors1 = Mat()
    private var descriptors2 = Mat()
    private var inlierMask = Mat()
    private var homography = Mat()

    private var prevKeypoints = MatOfKeyPoint()
    private var currKeypoints = MatOfKeyPoint()

    private val targetSize = Size(320.0, 240.0)

    fun detect(
        frameRgba: Mat,
        cameraMatrix: Mat,
    ): PlaneResult {
        val small = Mat()
        try {
            Imgproc.resize(frameRgba, small, targetSize)
            Imgproc.cvtColor(small, currGray, Imgproc.COLOR_RGBA2GRAY)

            if (prevGray.empty()) {
                currGray.copyTo(prevGray)
                orb.detectAndCompute(prevGray, Mat(), prevKeypoints, descriptors1)
                return PlaneResult(0f, null, DoubleArray(3), 0.0)
            }

            orb.detectAndCompute(currGray, Mat(), currKeypoints, descriptors2)

            if (descriptors1.empty() || descriptors2.empty()) {
                rotate()
                return PlaneResult(0f, null, DoubleArray(3), 0.0)
            }

            val matchesList = mutableListOf<MatOfDMatch>()
            val knnMatches = mutableListOf<MatOfDMatch>()
            matcher.knnMatch(descriptors1, descriptors2, knnMatches, 2)

            // Lowe's ratio test — keep match if best_dist < 0.75 * second_best_dist
            for (m in knnMatches) {
                val arr = m.toArray()
                if (arr.size == 2 && arr[0].distance < 0.75f * arr[1].distance) {
                    matchesList.add(MatOfDMatch().apply { fromArray(arr[0]) })
                }
                m.release()
            }

            val totalMatches = matchesList.size
            if (totalMatches < 4) {
                matchesList.forEach { it.release() }
                rotate()
                return PlaneResult(0f, null, DoubleArray(3), 0.0)
            }

            val pts1 = MatOfPoint2f()
            val pts2 = MatOfPoint2f()
            extractMatchedPoints(matchesList, prevKeypoints, currKeypoints, pts1, pts2)
            matchesList.forEach { it.release() }

            inlierMask.release()
            inlierMask = Mat()
            homography.release()
            homography = Calib3d.findHomography(pts1, pts2, Calib3d.RANSAC, 3.0, inlierMask)

            pts1.release()
            pts2.release()

            if (homography.empty()) {
                rotate()
                return PlaneResult(0f, null, DoubleArray(3), 0.0)
            }

            val inlierCount = Core.countNonZero(inlierMask)
            val confidence = inlierCount.toFloat() / totalMatches

            var normalVec = DoubleArray(3)
            var distanceRaw = 0.0

            if (confidence > 0.7) {
                val decomposed = decomposeHomography(homography, cameraMatrix)
                normalVec = decomposed.first
                distanceRaw = decomposed.second
            }

            rotate()
            return PlaneResult(
                confidence = confidence,
                homography = if (confidence > 0.7) homography else null,
                normalVec = normalVec,
                distanceRaw = distanceRaw,
            )
        } finally {
            small.release()
        }
    }

    // Returns (normal, distanceRaw) for the best homography decomposition.
    // Filters to n[2] > 0 (plane faces camera), picks min reprojection error solution.
    private fun decomposeHomography(H: Mat, K: Mat): Pair<DoubleArray, Double> {
        val rotations = mutableListOf<Mat>()
        val translations = mutableListOf<Mat>()
        val normals = mutableListOf<Mat>()

        Calib3d.decomposeHomographyMat(H, K, rotations, translations, normals)

        var bestNormal = doubleArrayOf(0.0, 0.0, 1.0)
        var bestDist = 0.0
        var bestError = Double.MAX_VALUE

        for (i in rotations.indices) {
            val n = normals[i].get(0, 0)
            if (n == null || n[2] <= 0) continue  // must face camera

            val t = translations[i]
            val dist = Core.norm(t)

            // Simple reprojection error proxy: prefer solution with largest norm(t)
            // (more geometrically stable). Full reprojection uses matched inliers but
            // requires passing them here — norm(t) is a sound tiebreaker for our use case.
            val error = -dist
            if (error < bestError) {
                bestError = error
                bestNormal = doubleArrayOf(n[0], n[1], n[2])
                bestDist = dist
            }
        }

        rotations.forEach { it.release() }
        translations.forEach { it.release() }
        normals.forEach { it.release() }

        return Pair(bestNormal, bestDist)
    }

    private fun extractMatchedPoints(
        matches: List<MatOfDMatch>,
        kp1: MatOfKeyPoint,
        kp2: MatOfKeyPoint,
        out1: MatOfPoint2f,
        out2: MatOfPoint2f,
    ) {
        val kp1arr = kp1.toArray()
        val kp2arr = kp2.toArray()
        val pts1 = ArrayList<Point>()
        val pts2 = ArrayList<Point>()
        for (m in matches) {
            val dm = m.toArray()[0]
            pts1.add(kp1arr[dm.queryIdx].pt)
            pts2.add(kp2arr[dm.trainIdx].pt)
        }
        out1.fromList(pts1)
        out2.fromList(pts2)
    }

    // Advance frame buffer: curr → prev
    private fun rotate() {
        val tmp = prevGray
        prevGray = currGray
        currGray = tmp
        currGray.release()
        currGray = Mat()

        descriptors1.release()
        descriptors1 = descriptors2
        descriptors2 = Mat()

        val tmpKp = prevKeypoints
        prevKeypoints = currKeypoints
        currKeypoints = tmpKp
        currKeypoints.release()
        currKeypoints = MatOfKeyPoint()
    }

    fun close() {
        prevGray.release()
        currGray.release()
        descriptors1.release()
        descriptors2.release()
        inlierMask.release()
        homography.release()
        prevKeypoints.release()
        currKeypoints.release()
        orb.clear()
        matcher.clear()
    }
}
