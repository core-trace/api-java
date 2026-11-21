package school.sptech.dto;

// DTO: representa o bloco "indicadores" (os KPIs da tela Geral) do JSON de /geral/resumo
public class IndicadoresDto {

    private int criticos;
    private String criticosLegenda;
    private int atencao;
    private String atencaoLegenda;
    private int semDadosRecentes;
    private String semDadosLegenda;

    public IndicadoresDto() {
    }

    public int getCriticos() {
        return criticos;
    }

    public void setCriticos(int criticos) {
        this.criticos = criticos;
    }

    public String getCriticosLegenda() {
        return criticosLegenda;
    }

    public void setCriticosLegenda(String criticosLegenda) {
        this.criticosLegenda = criticosLegenda;
    }

    public int getAtencao() {
        return atencao;
    }

    public void setAtencao(int atencao) {
        this.atencao = atencao;
    }

    public String getAtencaoLegenda() {
        return atencaoLegenda;
    }

    public void setAtencaoLegenda(String atencaoLegenda) {
        this.atencaoLegenda = atencaoLegenda;
    }

    public int getSemDadosRecentes() {
        return semDadosRecentes;
    }

    public void setSemDadosRecentes(int semDadosRecentes) {
        this.semDadosRecentes = semDadosRecentes;
    }

    public String getSemDadosLegenda() {
        return semDadosLegenda;
    }

    public void setSemDadosLegenda(String semDadosLegenda) {
        this.semDadosLegenda = semDadosLegenda;
    }
}
