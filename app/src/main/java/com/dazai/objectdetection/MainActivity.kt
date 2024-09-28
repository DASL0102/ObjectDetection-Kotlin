package com.dazai.objectdetection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.label.TensorLabel
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView // Para mostrar la imagen procesada
    private lateinit var textViewResult: TextView // Para mostrar los resultados de clasificación
    private lateinit var tflite: Interpreter // Intérprete del modelo TensorFlow Lite
    private lateinit var cameraExecutor: ExecutorService // Executor para el análisis de imágenes
    private lateinit var previewView: PreviewView // Vista previa de la cámara

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar vistas
        imageView = findViewById(R.id.imageView)
        textViewResult = findViewById(R.id.textViewResult)
        previewView = findViewById(R.id.previewView)

        // Cargar el modelo TensorFlow Lite
        tflite = Interpreter(loadModelFile("mobilenet_v1.tflite"))
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Iniciar la cámara
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        // Configurar la cámara al completar la inicialización
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Configurar la vista previa de la cámara
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider) // Usar previewView para la vista previa
            }

            // Configurar el análisis de imágenes
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(224, 224)) // Establecer la resolución de destino
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Estrategia de sobrepresión
                .build().also {
                    it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                        processImageProxy(imageProxy) // Procesar la imagen capturada
                    })
                }

            // Seleccionar la cámara trasera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Vincular el ciclo de vida de la cámara
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy?) {
        // Verificar si la imagen es nula
        if (imageProxy == null) {
            runOnUiThread {
                textViewResult.text = "No image available" // Mensaje si no hay imagen
            }
            return
        }

        // Convertir la imagen a Bitmap
        val bitmap = imageProxyToBitmap(imageProxy)

        // Verificar si se obtuvo un Bitmap válido
        if (bitmap != null) {
            classifyImage(bitmap) // Clasificar la imagen con el modelo
        } else {
            runOnUiThread {
                textViewResult.text = "No image available" // Mensaje si no se pudo convertir
            }
        }

        imageProxy.close() // Cerrar el proxy para liberar recursos
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val yPlane = image.planes[0] // Obtener el plano Y
        val uPlane = image.planes[1] // Obtener el plano U
        val vPlane = image.planes[2] // Obtener el plano V

        // Obtener los buffers de los planos
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        // Crear arrays de bytes a partir de los buffers
        val yBytes = ByteArray(yBuffer.remaining()).also { yBuffer.get(it) }
        val uBytes = ByteArray(uBuffer.remaining()).also { uBuffer.get(it) }
        val vBytes = ByteArray(vBuffer.remaining()).also { vBuffer.get(it) }

        // Crear un Bitmap del tamaño correspondiente
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(yBytes.size + uBytes.size + vBytes.size)

        // Llenar el array NV21 con los datos YUV
        System.arraycopy(yBytes, 0, nv21, 0, yBytes.size)
        for (i in 0 until uBytes.size / 2) {
            nv21[yBytes.size + i * 2] = vBytes[i] // V
            nv21[yBytes.size + i * 2 + 1] = uBytes[i] // U
        }

        // Convertir el NV21 a un Bitmap
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)

        // Devolver el Bitmap creado
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    private fun classifyImage(bitmap: Bitmap) {
        // Redimensionar la imagen a 224x224
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true)

        // Crear un TensorImage a partir del Bitmap
        val tensorImage = TensorImage(DataType.UINT8).apply {
            load(resizedBitmap)
        }

        // Crear un buffer de salida para las probabilidades
        val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 1001), DataType.UINT8)

        // Ejecutar la inferencia con el modelo
        tflite.run(tensorImage.buffer, outputBuffer.buffer.rewind())

        // Cargar las etiquetas desde el archivo
        val labels = assets.open("labels_mobilenet.txt").bufferedReader().use { it.readLines() }

        // Mapear las probabilidades con las etiquetas
        val labeledProbabilities = TensorLabel(labels, outputBuffer).mapWithFloatValue

        // Definir un umbral de confianza
        val confidenceThreshold = 0.5f // 50%

        // Filtrar los resultados por el umbral de confianza
        val filteredResults = labeledProbabilities.filter { it.value >= confidenceThreshold }

        // Actualizar la UI con los resultados
        runOnUiThread {
            if (filteredResults.isNotEmpty()) {
                val maxLabelEntry = filteredResults.maxByOrNull { it.value }
                val maxLabel = maxLabelEntry?.key ?: "Unknown"
                val confidenceValue = maxLabelEntry?.value ?: 0.0f

                // Calcular el porcentaje de confianza
                val percentage = (confidenceValue * 100).coerceIn(0f, 100f)
                textViewResult.text = "$maxLabel ($percentage%)" // Mostrar el resultado
            } else {
                textViewResult.text = "No confident predictions" // Mensaje si no hay predicciones confiables
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cerrar el intérprete y liberar el executor
        tflite.close()
        cameraExecutor.shutdown()
    }

    // Función para cargar el archivo del modelo
    @Throws(IOException::class)
    private fun loadModelFile(modelFilename: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(modelFilename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength) // Mapear el archivo del modelo
    }
}
