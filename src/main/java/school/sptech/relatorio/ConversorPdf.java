package school.sptech.relatorio;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

// Converte o html em relatorio na memoria com bytearrayoutput pra nunca correr o risco de pegar um arquivo incompleto

public class ConversorPdf {

    public byte[] converterParaPdf(String html) throws IOException {
        try (ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(saida);
            builder.run();

            return saida.toByteArray();
        }
    }
}