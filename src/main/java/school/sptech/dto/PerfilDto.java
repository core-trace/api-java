package school.sptech.dto;

// DTO: representa um item do bloco "perfis" (a tabela "Saúde por perfil") do JSON de /geral/resumo
public class PerfilDto {

    private String nome;
    private String sinal;
    private String afetados;
    private String proporcao;
    private String descricao;
    private String duracao;
    private String referencia;

    public PerfilDto() {
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getSinal() {
        return sinal;
    }

    public void setSinal(String sinal) {
        this.sinal = sinal;
    }

    public String getAfetados() {
        return afetados;
    }

    public void setAfetados(String afetados) {
        this.afetados = afetados;
    }

    public String getProporcao() {
        return proporcao;
    }

    public void setProporcao(String proporcao) {
        this.proporcao = proporcao;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public String getDuracao() {
        return duracao;
    }

    public void setDuracao(String duracao) {
        this.duracao = duracao;
    }

    public String getReferencia() {
        return referencia;
    }

    public void setReferencia(String referencia) {
        this.referencia = referencia;
    }
}
