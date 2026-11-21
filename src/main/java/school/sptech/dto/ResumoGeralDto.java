package school.sptech.dto;

import java.util.List;

// DTO: representa a raiz do JSON devolvido por GET /geral/resumo (website).
// So traz os campos que o relatorio em PDF usa e a tela usa mais campos
// (sinaisExternos, evolucao) ficam de fora do relatorio.
public class ResumoGeralDto {

    private EstadoDto estado;
    private CoberturaDto cobertura;
    private IndicadoresDto indicadores;
    private List<PerfilDto> perfis;
    private ResumoSituacaoDto resumo;
    private List<AcaoDto> acoes;

    public ResumoGeralDto() {
    }

    public EstadoDto getEstado() {
        return estado;
    }

    public void setEstado(EstadoDto estado) {
        this.estado = estado;
    }

    public CoberturaDto getCobertura() {
        return cobertura;
    }

    public void setCobertura(CoberturaDto cobertura) {
        this.cobertura = cobertura;
    }

    public IndicadoresDto getIndicadores() {
        return indicadores;
    }

    public void setIndicadores(IndicadoresDto indicadores) {
        this.indicadores = indicadores;
    }

    public List<PerfilDto> getPerfis() {
        return perfis;
    }

    public void setPerfis(List<PerfilDto> perfis) {
        this.perfis = perfis;
    }

    public ResumoSituacaoDto getResumo() {
        return resumo;
    }

    public void setResumo(ResumoSituacaoDto resumo) {
        this.resumo = resumo;
    }

    public List<AcaoDto> getAcoes() {
        return acoes;
    }

    public void setAcoes(List<AcaoDto> acoes) {
        this.acoes = acoes;
    }
}
