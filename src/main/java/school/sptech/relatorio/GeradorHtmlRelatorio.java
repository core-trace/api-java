package school.sptech.relatorio;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import school.sptech.dto.ResumoGeralDto;

// Preenche o html com os dados

public class GeradorHtmlRelatorio {

    private final TemplateEngine templateEngine;

    public GeradorHtmlRelatorio() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        this.templateEngine = new TemplateEngine();
        this.templateEngine.setTemplateResolver(resolver);
    }

    public String gerarHtmlRelatorioGeral(ResumoGeralDto resumo) {
        Context contexto = new Context();
        contexto.setVariable("estado", resumo.getEstado());
        contexto.setVariable("indicadores", resumo.getIndicadores());
        contexto.setVariable("perfis", resumo.getPerfis());
        contexto.setVariable("resumo", resumo.getResumo());
        contexto.setVariable("acoes", resumo.getAcoes());

        return templateEngine.process("relatorio-geral", contexto);
    }
}
