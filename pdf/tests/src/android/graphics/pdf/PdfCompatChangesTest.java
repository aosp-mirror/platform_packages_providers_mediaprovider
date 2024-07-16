/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics.pdf;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.RawRes;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.BitmapUtils;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Tests covering compat changes in {@link PdfRenderer} */
@RunWith(Parameterized.class)
public class PdfCompatChangesTest {
    private static final String LOG_TAG = "PdfRendererScreenshotTest";
    private static final String LOCAL_DIRECTORY =
            Environment.getExternalStorageDirectory() + "/PdfRendererScreenshotTest";
    private static final Map<Integer, File> sFiles = new ArrayMap<>();

    private static final int CLICK_FORM = R.raw.click_form;
    private static final int COMBOBOX_FORM = R.raw.combobox_form;
    private static final int LISTBOX_FORM = R.raw.listbox_form;
    private static final int TEXT_FORM = R.raw.text_form;

    private static final int CLICK_FORM_GOLDEN = R.drawable.click_form_golden;
    private static final int COMBOBOX_FORM_GOLDEN = R.drawable.combobox_form_golden;
    private static final int LISTBOX_FORM_GOLDEN = R.drawable.listbox_form_golden;
    private static final int TEXT_FORM_GOLDEN = R.drawable.text_form_golden;

    private static final int CLICK_FORM_GOLDEN_NOFORM = R.drawable.click_noform_golden;
    private static final int COMBOBOX_FORM_GOLDEN_NOFORM = R.drawable.combobox_noform_golden;
    private static final int LISTBOX_FORM_GOLDEN_NOFORM = R.drawable.listbox_noform_golden;
    private static final int TEXT_FORM_GOLDEN_NO_FORM = R.drawable.text_noform_golden;

    @Rule
    public final TestRule mCompatChangeRule = new PlatformCompatChangeRule();

    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final int mPdfRes;
    private final int mGoldenRes;
    private final int mGoldenResNoForm;
    private final String mPdfName;

    public PdfCompatChangesTest(@RawRes int pdfRes, @DrawableRes int goldenRes,
            @DrawableRes int goldenResNoForm, String pdfName) {
        mPdfRes = pdfRes;
        mGoldenRes = goldenRes;
        mGoldenResNoForm = goldenResNoForm;
        mPdfName = pdfName;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> getParameters() {
        List<Object[]> parameters = new ArrayList<>();
        parameters.add(new Object[]{CLICK_FORM, CLICK_FORM_GOLDEN, CLICK_FORM_GOLDEN_NOFORM,
                "click_form"});
        parameters.add(
                new Object[]{COMBOBOX_FORM, COMBOBOX_FORM_GOLDEN, COMBOBOX_FORM_GOLDEN_NOFORM,
                        "combobox_form"});
        parameters.add(new Object[]{LISTBOX_FORM, LISTBOX_FORM_GOLDEN, LISTBOX_FORM_GOLDEN_NOFORM,
                "listbox_form"});
        parameters.add(
                new Object[]{TEXT_FORM, TEXT_FORM_GOLDEN, TEXT_FORM_GOLDEN_NO_FORM, "text_form"});
        return parameters;
    }

    @Test
    @EnableCompatChanges({PdfRenderer.RENDER_PDF_FORM_FIELDS})
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM, codeName =
            "VanillaIceCream")
    public void renderFormContentWhenEnabled() throws Exception {
        renderAndCompare(mPdfName + "-form", mGoldenRes);
    }

    @Test
    @DisableCompatChanges({PdfRenderer.RENDER_PDF_FORM_FIELDS})
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM, codeName =
            "VanillaIceCream")
    public void doNotRenderFormContentWhenDisabled() throws Exception {
        renderAndCompare(mPdfName + "-noform", mGoldenResNoForm);
    }

    private void renderAndCompare(String testName, int goldenRes) throws IOException {
        try (PdfRenderer renderer = new PdfRenderer(
                getParcelFileDescriptorFromResourceId(mPdfRes, mContext))) {
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                Bitmap golden = BitmapFactory.decodeResource(mContext.getResources(), goldenRes,
                        options);
                Bitmap output = Bitmap.createBitmap(page.getWidth(), page.getHeight(),
                        Bitmap.Config.ARGB_8888);
                page.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                assertScreenshotsAreEqual(golden, output, testName, LOCAL_DIRECTORY);
            }
        }
    }

    private static void assertScreenshotsAreEqual(Bitmap before, Bitmap after, String testName,
            String localDir) {
        if (!BitmapUtils.compareBitmaps(before, after)) {
            File beforeFile = null;
            File afterFile = null;
            try {
                beforeFile = dumpBitmap(before, testName + "-golden.png", localDir);
                afterFile = dumpBitmap(after, testName + "-test.png", localDir);
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error dumping bitmap", e);
            }
            Assert.fail(
                    "Screenshots do not match (check " + beforeFile + " and " + afterFile + ")");
        }
    }

    private static File dumpBitmap(Bitmap bitmap, String filename, String localDir)
            throws IOException {
        File file = createFile(filename, localDir);
        if (file == null) return null;
        Log.i(LOG_TAG, "Dumping bitmap at " + file);
        BitmapUtils.saveBitmap(bitmap, file.getParent(), file.getName());
        return file;

    }

    private static File createFile(String filename, String localDir) throws IOException {
        File dir = getLocalDirectory(localDir);
        File file = new File(dir, filename);
        if (file.exists()) {
            Log.v(LOG_TAG, "Deleting file " + file);
            file.delete();
        }
        if (!file.createNewFile()) {
            Log.e(LOG_TAG, "couldn't create new file");
            return null;
        }
        return file;
    }

    private static File getLocalDirectory(String localDir) {
        File dir = new File(localDir);
        dir.mkdirs();
        if (!dir.exists()) {
            Log.e(LOG_TAG, "couldn't create directory");
            return null;
        }
        return dir;
    }

    /**
     * Create a {@link ParcelFileDescriptor} pointing to a file copied from a resource.
     *
     * @param docRes  The resource to load
     * @param context The context to use for creating the parcel file descriptor
     * @return the ParcelFileDescriptor
     * @throws IOException If anything went wrong
     */
    @NonNull
    private static ParcelFileDescriptor getParcelFileDescriptorFromResourceId(@RawRes int docRes,
            @NonNull Context context) throws IOException {
        File pdfFile = sFiles.get(docRes);
        if (pdfFile == null) {
            pdfFile = File.createTempFile("pdf", null, context.getCacheDir());
            // Copy resource to file so that we can open it as a ParcelFileDescriptor

            InputStream inputStream = context.getResources().openRawResource(docRes);
            // Create a FileOutputStream to write the resource content to the target file.
            FileOutputStream outputStream = new FileOutputStream(pdfFile);

            // Copy the content of the resource file to the target file.
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }

            // Close streams.
            inputStream.close();
            outputStream.close();
            sFiles.put(docRes, pdfFile);
        }
        return Objects.requireNonNull(
                ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY));
    }
}
