package school.sptech.dto;

// DTO: representa um item de "resumo.comparacao" do JSON de /geral/resumo
public class ComparacaoPerfilDto {

    private String perfil;
    private String afetados;
    private String duracao;

    public ComparacaoPerfilDto() {
    }

    public String getPerfil() {
        return perfil;
    }

    public void setPerfil(String perfil) {
        this.perfil = perfil;
    }

    public String getAfetados() {
        return afetados;
    }

    public void setAfetados(String afetados) {
        this.afetados = afetados;
    }

    public String getDuracao() {
        return duracao;
    }

    public void setDuracao(String duracao) {
        this.duracao = duracao;
    }
}
