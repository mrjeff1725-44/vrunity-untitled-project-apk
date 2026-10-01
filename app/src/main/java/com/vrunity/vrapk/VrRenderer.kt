package com.vrunity.vrapk

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// The game itself: the scene's geometry on the GPU, drawn once for each eye with
// the head's real orientation from the headset's motion sensors. Nothing here is
// web — no page, no browser, no WebView.
class VrRenderer(private val context: Context) : GLSurfaceView.Renderer {
    private val shapes = HashMap<String, Mesh>()
    private val items = ArrayList<Item>()

    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uColor = 0
    private var uLight = 0
    private var uAmbient = 0
    private var width = 1
    private var height = 1

    private val bg = floatArrayOf(0.06f, 0.08f, 0.14f)
    private val player = floatArrayOf(0f, 1.6f, 0f)
    private var rigYaw = 0f
    private val lightWorld = floatArrayOf(0.40f, 0.78f, 0.48f)
    private var ambient = 0.32f

    // Head tracking. The sensor reports the device's orientation; how the screen's
    // upright sits inside the headset is worked out from the sensors themselves
    // instead of assumed, so the horizon comes out level on any holder.
    @Volatile private var sample: FloatArray? = null
    @Volatile private var askRecenter = false
    @Volatile private var walk = 0f
    @Volatile private var dragYaw = 0f
    @Volatile private var dragPitch = 0f
    private val rots = floatArrayOf(0f, 90f, 180f, 270f)
    private val scores = FloatArray(4)
    private var roll = 0f
    private var worn = false
    private var yawRef = 0f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val scratch = FloatArray(16)
    private val deviceM = FloatArray(16)
    private val rollM = FloatArray(16)
    private val effM = FloatArray(16)
    private val sceneM = FloatArray(16)
    private val sceneT = FloatArray(16)
    private val convT = FloatArray(16)
    private val yawM = FloatArray(16)
    private val pitchM = FloatArray(16)
    private val camM = FloatArray(16)
    private val moveM = FloatArray(16)

    // Android's world axes (X east, Y north, Z up) turned into the scene's (Y up).
    private val BASIS = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 0f, -1f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f)

    fun setHead(m: FloatArray) { sample = m }
    fun walkBy(dir: Float) { walk += dir }
    fun recenter() { askRecenter = true }
    fun drag(dx: Float, dy: Float) { dragYaw -= dx; dragPitch -= dy }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        shapes["cube"] = Mesh(Mesh.box())
        shapes["sphere"] = Mesh(Mesh.sphere())
        shapes["cylinder"] = Mesh(Mesh.cylinder())
        shapes["cone"] = Mesh(Mesh.cone())
        shapes["plane"] = Mesh(Mesh.plane())
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        buildProgram()
        loadScene()
        GLES20.glClearColor(bg[0], bg[1], bg[2], 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = if (w < 1) 1 else w
        height = if (h < 1) 1 else h
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw RuntimeException("Shader failed: " + log)
        }
        return id
    }

    private fun buildProgram() {
        val vertex = "uniform mat4 uMvp;attribute vec3 aPos;attribute vec3 aNormal;varying vec3 vN;" +
            "void main(){vN=aNormal;gl_Position=uMvp*vec4(aPos,1.0);}"
        val fragment = "precision mediump float;uniform vec3 uColor;uniform vec3 uLight;uniform float uAmbient;" +
            "varying vec3 vN;void main(){float d=max(dot(normalize(vN),normalize(uLight)),0.0);" +
            "vec3 c=uColor*(uAmbient+(1.0-uAmbient)*d);gl_FragColor=vec4(pow(c,vec3(0.4545)),1.0);}"
        val vs = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Program failed: " + GLES20.glGetProgramInfoLog(program))
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uAmbient = GLES20.glGetUniformLocation(program, "uAmbient")
        GLES20.glUseProgram(program)
    }

    private fun vec(a: JSONArray?, fallback: FloatArray): FloatArray {
        if (a == null || a.length() < 3) return floatArrayOf(fallback[0], fallback[1], fallback[2])
        return floatArrayOf(a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(), a.optDouble(2, 0.0).toFloat())
    }

    private fun loadScene() {
        items.clear()
        val text = context.assets.open("scene.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val bgArr = vec(root.optJSONArray("bg"), floatArrayOf(0.06f, 0.08f, 0.14f))
        bg[0] = bgArr[0]; bg[1] = bgArr[1]; bg[2] = bgArr[2]
        val start = root.optJSONObject("start")
        if (start != null) {
            val p = vec(start.optJSONArray("pos"), floatArrayOf(0f, 0f, 0f))
            rigYaw = start.optDouble("yaw", 0.0).toFloat()
            player[0] = p[0]
            player[2] = p[2]
        }
        player[1] = root.optDouble("eyeHeight", 1.6).toFloat()
        val list = root.optJSONArray("objects") ?: return
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val shape = o.optString("shape", "")
            if (!shapes.containsKey(shape)) continue
            val p = vec(o.optJSONArray("pos"), floatArrayOf(0f, 0f, 0f))
            val rot = vec(o.optJSONArray("rot"), floatArrayOf(0f, 0f, 0f))
            val scl = vec(o.optJSONArray("scale"), floatArrayOf(1f, 1f, 1f))
            val col = vec(o.optJSONArray("color"), floatArrayOf(0.5f, 0.5f, 0.5f))
            val rotation = FloatArray(16)
            Matrix.setIdentityM(rotation, 0)
            Matrix.rotateM(rotation, 0, (rot[0] * 57.29578f), 1f, 0f, 0f)
            Matrix.rotateM(rotation, 0, (rot[1] * 57.29578f), 0f, 1f, 0f)
            Matrix.rotateM(rotation, 0, (rot[2] * 57.29578f), 0f, 0f, 1f)
            val placed = FloatArray(16)
            Matrix.setIdentityM(placed, 0)
            Matrix.translateM(placed, 0, p[0], p[1], p[2])
            val model = FloatArray(16)
            Matrix.multiplyMM(model, 0, placed, 0, rotation, 0)
            Matrix.scaleM(model, 0, scl[0], scl[1], scl[2])
            // The light is turned into the object's own frame once, so the shader
            // needs no normal matrix and stays tiny.
            val lx = lightWorld[0]; val ly = lightWorld[1]; val lz = lightWorld[2]
            val light = floatArrayOf(
                rotation[0] * lx + rotation[4] * ly + rotation[8] * lz,
                rotation[1] * lx + rotation[5] * ly + rotation[9] * lz,
                rotation[2] * lx + rotation[6] * ly + rotation[10] * lz)
            items.add(Item(shape, model, col, light))
        }
    }

    // The sensor's rotation matrix, laid into a 4x4, plus the roll that makes the
    // screen's upright the headset's upright.
    private fun headMatrix(m: FloatArray) {
        deviceM[0] = m[0]; deviceM[1] = m[3]; deviceM[2] = m[6]; deviceM[3] = 0f
        deviceM[4] = m[1]; deviceM[5] = m[4]; deviceM[6] = m[7]; deviceM[7] = 0f
        deviceM[8] = m[2]; deviceM[9] = m[5]; deviceM[10] = m[8]; deviceM[11] = 0f
        deviceM[12] = 0f; deviceM[13] = 0f; deviceM[14] = 0f; deviceM[15] = 1f
    }

    private fun updateTracking(m: FloatArray) {
        headMatrix(m)
        if (!worn) {
            for (i in 0 until 4) {
                val b = Math.toRadians(rots[i].toDouble())
                val x = (-Math.sin(b)).toFloat()
                val y = Math.cos(b).toFloat()
                val upWorld = m[6] * x + m[7] * y
                scores[i] = scores[i] * 0.9f + upWorld
            }
            var best = 0
            for (i in 1 until 4) if (scores[i] > scores[best]) best = i
            roll = rots[best]
        }
        Matrix.setRotateM(rollM, 0, roll, 0f, 0f, 1f)
        Matrix.multiplyMM(effM, 0, deviceM, 0, rollM, 0)
        Matrix.multiplyMM(scratch, 0, BASIS, 0, effM, 0)
        Matrix.transposeM(convT, 0, BASIS, 0)
        Matrix.multiplyMM(sceneM, 0, scratch, 0, convT, 0)
        val fx = -sceneM[8]
        val fy = -sceneM[9]
        val fz = -sceneM[10]
        if (!worn && Math.abs(fy) < 0.40f && Math.abs(fx) + Math.abs(fz) > 0.1f) {
            worn = true
            yawRef = Math.atan2(fx.toDouble(), fz.toDouble()).toFloat()
        }
        if (askRecenter) {
            askRecenter = false
            worn = true
            yawRef = Math.atan2(fx.toDouble(), fz.toDouble()).toFloat()
        }
    }

    private fun buildView(eyeOffset: Float) {
        val s = sample
        Matrix.setIdentityM(sceneT, 0)
        var yawNow = 0f
        if (s != null) {
            updateTracking(s)
            Matrix.transposeM(sceneT, 0, sceneM, 0)
            val fx = -sceneM[8]
            val fz = -sceneM[10]
            yawNow = Math.atan2(fx.toDouble(), fz.toDouble()).toFloat()
        }
        val extraYaw = rigYaw + (yawNow - yawRef) + dragYaw
        Matrix.setRotateM(yawM, 0, -extraYaw * 57.29578f, 0f, 1f, 0f)
        Matrix.multiplyMM(camM, 0, yawM, 0, sceneT, 0)
        if (dragPitch != 0f) {
            Matrix.setRotateM(pitchM, 0, -dragPitch * 57.29578f, 1f, 0f, 0f)
            Matrix.multiplyMM(scratch, 0, camM, 0, pitchM, 0)
            System.arraycopy(scratch, 0, camM, 0, 16)
        }
        // Standing in the scene wherever the player has walked to.
        val move = walk
        if (move != 0f) {
            walk = 0f
            val fx = -camM[2]
            val fz = -camM[10]
            val len = Math.hypot(fx.toDouble(), fz.toDouble()).toFloat()
            if (len > 0.001f) {
                player[0] += (fx / len) * 0.7f * move
                player[2] += (fz / len) * 0.7f * move
            }
        }
        val rx = camM[0]
        val ry = camM[4]
        val rz = camM[8]
        val ex = player[0] + rx * eyeOffset
        val ey = player[1] + ry * eyeOffset
        val ez = player[2] + rz * eyeOffset
        Matrix.setIdentityM(moveM, 0)
        Matrix.translateM(moveM, 0, -ex, -ey, -ez)
        Matrix.multiplyMM(view, 0, camM, 0, moveM, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val half = width / 2
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.perspectiveM(proj, 0, 72f, half.toFloat() / height.toFloat(), 0.05f, 600f)
        GLES20.glUseProgram(program)
        GLES20.glUniform3f(uLight, 0f, 0f, 0f)
        GLES20.glUniform1f(uAmbient, ambient)
        for (eye in 0 until 2) {
            val offset = if (eye == 0) -0.032f else 0.032f
            buildView(offset)
            GLES20.glViewport(eye * half, 0, half, height)
            for (i in items.indices) {
                val item = items[i]
                Matrix.multiplyMM(scratch, 0, view, 0, item.model, 0)
                Matrix.multiplyMM(mvp, 0, proj, 0, scratch, 0)
                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
                GLES20.glUniform3f(uColor, item.color[0], item.color[1], item.color[2])
                GLES20.glUniform3f(uLight, item.light[0], item.light[1], item.light[2])
                val mesh = shapes[item.shape]
                if (mesh != null) mesh.draw(aPos, aNormal)
            }
        }
    }

    private class Item(val shape: String, val model: FloatArray, val color: FloatArray, val light: FloatArray)
}
