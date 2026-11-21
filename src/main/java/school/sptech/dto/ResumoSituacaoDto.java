package school.sptech.dto;

import java.util.List;

// DTO: representa o bloco "resumo" (o card "Resumo da situação") do JSON de /geral/resumo
public class ResumoSituacaoDto {

    private String titulo;
    private String motivo;
    private List<ComparacaoPerfilDto> comparacao;
    private String cobertura;

    public ResumoSituacaoDto() {
    }

    public String getTitulo() {
        return titulo;
    }

    public void setTitulo(String titulo) {
        this.titulo = titulo;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }

    public List<ComparacaoPerfilDto> getComparacao() {
        return comparacao;
    }

    public void setComparacao(List<ComparacaoPerfilDto> comparacao) {
        this.comparacao = comparacao;
    }

    public String getCobertura() {
        return cobertura;
    }

    public void setCobertura(String cobertura) {
        this.cobertura = cobertura;
    }
}
