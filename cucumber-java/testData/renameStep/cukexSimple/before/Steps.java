import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class Steps {

  @When("I am happy")
  @Then("I am happy")
  public void i_am_happy() {
  }

  @When("I am dumb")
  public void i_am_dumb() {
  }

  @When("unrelated step")
  public void unrelated_step() {
  }
}
