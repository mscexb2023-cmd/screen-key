package ru.mscexb.screenkey

import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs

/**
 * Анализатор кадра с фронтальной камеры: отвечает «виден ли строго фронтальный зритель».
 *
 * Условия «чётко»:
 *  - в кадре ровно ОДНО лицо (второе лицо в кадре = сосед подсматривает → блюр);
 *  - лицо в пределах допустимого смещения от центра кадра;
 *  - повороты головы в пределах порогов.
 *
 * Все пороги читаются из SharedPreferences — пользователь редактирует их
 * в «Параметрах», изменения применяются сразу, без перезапуска.
 *
 * Гистерезис (кадры до открытия/до блюра) тоже настраивается пользователем.
 */
class FaceAnalyzer(
    private val prefs: SharedPreferences,
    private val onVerdict: (Boolean) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    private var passRun = 0
    private var failRun = 0

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val w = imageProxy.width
        val h = imageProxy.height
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { faces -> onVerdict(evaluate(faces, w, h)) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun evaluate(faces: List<Face>, w: Int, h: Int): Boolean {
        val ok = faces.size == 1 && isFrontal(faces[0], w, h)
        val passN = prefs.getInt(KeyService.KEY_PASS, KeyService.DEFAULT_PASS).coerceAtLeast(1)
        val failN = prefs.getInt(KeyService.KEY_FAIL, KeyService.DEFAULT_FAIL).coerceAtLeast(1)
        return if (ok) {
            passRun++
            failRun = 0
            passRun >= passN
        } else {
            failRun++
            passRun = 0
            failRun >= failN
        }
    }

    private fun isFrontal(face: Face, w: Int, h: Int): Boolean {
        val yawMax = prefs.getInt(KeyService.KEY_YAW, KeyService.DEFAULT_YAW)
        val rollMax = prefs.getInt(KeyService.KEY_ROLL, KeyService.DEFAULT_ROLL)
        val center = prefs.getInt(KeyService.KEY_CENTER, KeyService.DEFAULT_CENTER) / 100f
        if (abs(face.headEulerAngleY) > yawMax) return false   // поворот головы влево/вправо
        if (abs(face.headEulerAngleZ) > rollMax) return false  // наклон головы
        val bb = face.boundingBox
        val dx = (bb.centerX() - w / 2f) / w
        val dy = (bb.centerY() - h / 2f) / h
        return dx * dx + dy * dy <= center * center
    }

    fun close() {
        detector.close()
    }
}
