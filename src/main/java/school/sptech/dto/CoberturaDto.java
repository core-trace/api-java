package school.sptech.dto;

// DTO: representa o bloco "cobertura" do JSON de /geral/resumo
public class CoberturaDto {

    private int avaliados;
    private int esperados;

    public CoberturaDto() {
    }

    public int getAvaliados() {
        return avaliados;
    }

    public void setAvaliados(int avaliados) {
        this.avaliados = avaliados;
    }

    public int getEsperados() {
        return esperados;
    }

    public void setEsperados(int esperados) {
        this.esperados = esperados;
    }
}
